package com.nomkeyboard.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.nomkeyboard.app.R
import kotlin.math.max

/**
 * Horizontal scrollable candidate bar. Displays the currently composing Vietnamese text
 * on the left and the matching Nom character suggestions on the right. Designed after
 * the Gboard candidate strip. The internal Nom font is used to guarantee correct glyphs.
 *
 * Also supports an "expanded" mode: the whole IME surface is reused as a grid of
 * candidates. Entered/exited by tapping the chevron button on the far right of the
 * strip. When expanded, the bar reports its preferred height via [setExpandedHeight]
 * and lays out candidates in multiple rows.
 */
class CandidateBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    interface OnCandidatePickListener {
        fun onPickCandidate(index: Int, text: String)
        /** Fired when the user toggles the expand chevron. */
        fun onToggleExpand(expanded: Boolean) {}
    }

    var listener: OnCandidatePickListener? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.kb_candidate_text_size)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        // The composing label uses its own dedicated size so it stays visually calm even if
        // the main keyboard's key-text size changes later.
        textSize = resources.getDimension(R.dimen.kb_candidate_label_text_size)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Opaque fill used to paint a background rectangle underneath the chevron so it
    // actually occludes whatever candidate is scrolled behind it. Colour is kept in
    // sync with the candidate-strip background in [applyTheme].
    private val chevronBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var theme: KeyboardTheme = KeyboardTheme.light(context)

    init {
        // Seed paints that depend on the theme so the very first onDraw (before the IME
        // has a chance to call applyTheme explicitly) already uses sensible colours.
        // Without this the chevron would temporarily paint a BLACK occluder over the
        // bar, swallowing the ▼ icon itself on the first frame.
        chevronBgPaint.color = theme.candidateBg
        chevronPaint.color = theme.text
        dividerPaint.color = theme.divider
        highlightPaint.color = theme.press
        textPaint.color = theme.candidateText
        labelPaint.color = theme.text
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.candidateBg)
        textPaint.color = t.candidateText
        labelPaint.color = t.text
        dividerPaint.color = t.divider
        highlightPaint.color = t.press
        chevronBgPaint.color = t.candidateBg
        chevronPaint.color = t.text
        invalidate()
    }

    fun setTypeface(tf: Typeface?) {
        val face = tf ?: Typeface.DEFAULT
        // Both paints get the same typeface. `textPaint` draws the Nom candidates on the
        // right; `labelPaint` draws the "current composing" strip on the left. The
        // composing text is normally pure quốc ngữ so the Nom font makes no visual
        // difference there – BUT some flows (e.g. committing a candidate and continuing
        // to type, or the raw-commit escape path) can leave Nom glyphs mixed in with the
        // Vietnamese letters. Falling back to Typeface.DEFAULT for the label would then
        // render those Nom codepoints as tofu boxes, so we keep the two paints in sync.
        textPaint.typeface = face
        labelPaint.typeface = face
        invalidate()
    }

    // Base font sizes (px) read from resources at construction time. We keep them so the
    // user-adjustable scale can always be applied on top of the original design values
    // without drift after repeated toggles.
    private val baseTextSize = resources.getDimension(R.dimen.kb_candidate_text_size)
    private val baseLabelTextSize = resources.getDimension(R.dimen.kb_candidate_label_text_size)

    /**
     * Apply a multiplicative scale to both the Nom candidate text and the left-side composing
     * label. 1.0 = the resource default. Call this whenever the user changes the
     * "candidate bar font size" preference.
     */
    fun setCandidateTextScale(scale: Float) {
        val s = scale.coerceIn(0.6f, 2.0f)
        textPaint.textSize = baseTextSize * s
        labelPaint.textSize = baseLabelTextSize * s
        layoutCandidates()
        invalidate()
    }

    // The current composing string (rendered on the left in the default font)
    private var composing: String = ""

    // Hit-box of the composing label area – updated on every layout/draw so that taps on the
    // left "quốc ngữ" region can be detected and translated into a "commit composing as-is"
    // action. RectF.isEmpty() means the area is not interactive (empty composing).
    private val composingRect = RectF()

    // Candidate list
    private var candidates: List<String> = emptyList()
    private val candidateRects = ArrayList<RectF>()

    private var scrollX = 0f
    private var maxScrollX = 0f
    // Vertical scroll used only in expanded mode when candidates overflow the grid.
    private var scrollY = 0f
    private var maxScrollY = 0f

    private var pressedIndex = -1

    // ============================ Expand / collapse ============================

    /** When true the bar occupies multiple rows and hides the main keyboard. */
    private var expanded = false
    /** Baseline bar height (matches R.dimen.kb_candidate_height), set by the parent layout. */
    private val collapsedRowHeight = resources.getDimension(R.dimen.kb_candidate_height)
    /** Rect of the chevron hit-area on the far right; recomputed during layout. */
    private val chevronRect = RectF()
    /** Width reserved for the chevron button in px. */
    private val chevronW = resources.getDimension(R.dimen.kb_candidate_height) * 0.9f
    /**
     * True when the chevron affordance should be drawn / hit-tested. We only show it when
     * either (a) the candidates actually overflow the visible strip – in which case it is
     * the only way for the user to see the rest – or (b) we are already in expanded mode,
     * so the user has a way back to the single-row layout.
     */
    private var chevronVisible = false

    fun isExpanded() = expanded

    /**
     * Programmatically collapse the bar (e.g. when composing is cleared or the IME loses
     * focus). No-op if already collapsed. Does NOT fire [OnCandidatePickListener.onToggleExpand]
     * so the caller is responsible for restoring the keyboard view in its own state.
     */
    fun collapseSilently() {
        if (!expanded) return
        expanded = false
        scrollY = 0f
        layoutCandidates()
        invalidate()
    }

    fun setComposing(text: String) {
        if (composing != text) {
            composing = text
            scrollX = 0f
            invalidate()
            requestLayout()
        }
    }

    fun setCandidates(list: List<String>) {
        candidates = list
        scrollX = 0f
        scrollY = 0f
        layoutCandidates()
        invalidate()
    }

    fun clear() {
        composing = ""
        candidates = emptyList()
        scrollX = 0f
        scrollY = 0f
        // Auto-collapse when there is nothing to display – keeps UX consistent with the
        // way the bar is hidden by the IME when composing becomes empty.
        if (expanded) {
            expanded = false
            listener?.onToggleExpand(false)
        }
        layoutCandidates()
        invalidate()
    }

    private val padH = 24f    // horizontal padding inside each candidate cell
    private val gap = 8f      // gap between cells

    private fun layoutCandidates() {
        candidateRects.clear()
        val h = height.toFloat()
        if (h <= 0) return

        if (!expanded) {
            layoutSingleRow(h)
        } else {
            layoutGrid()
        }
        // Finalise the chevron rect / visibility AFTER the layout pass has computed the
        // total content width, so we know whether the strip overflows and therefore whether
        // the chevron is actually needed.
        if (chevronVisible) {
            chevronRect.set(width - chevronW, 0f, width.toFloat(), collapsedRowHeight)
        } else {
            chevronRect.setEmpty()
        }
    }

    private fun layoutSingleRow(h: Float) {
        // First pass: lay the composing label and all candidates out left-to-right in an
        // *infinite* strip, so we can measure the total content width and decide whether a
        // chevron is needed. We then rewind and, if the chevron is needed, shrink the last
        // candidates' right edge so they never sit under the chevron.
        var x = 0f
        if (composing.isNotEmpty()) {
            val w = labelPaint.measureText(composing) + padH * 2
            composingRect.set(0f, 0f, w, h)
            x += w
        } else {
            composingRect.setEmpty()
        }
        val starts = FloatArray(candidates.size)
        val ends = FloatArray(candidates.size)
        for ((i, c) in candidates.withIndex()) {
            val tw = max(textPaint.measureText(c), 28f) + padH * 2
            starts[i] = x
            ends[i] = x + tw
            x += tw + gap
        }
        val totalContentWidth = x
        val viewportW = width.toFloat()
        // The chevron is only useful when the content can't fit; otherwise it would just
        // eat horizontal space and partially cover the rightmost candidate.
        val overflows = totalContentWidth > viewportW
        chevronVisible = overflows
        // Effective right edge that candidates are allowed to occupy.
        val rightLimit = if (overflows) viewportW - chevronW else viewportW
        for (i in candidates.indices) {
            // Keep each candidate's cell at its natural width. We do NOT clamp the right
            // edge to `rightLimit` here: the cell is in CONTENT coordinates (the strip
            // scrolls horizontally) so clamping would collapse every off-screen cell's
            // right edge onto the same x and make their glyphs stack on top of each
            // other when the user scrolls. Instead the chevron is drawn on top of the
            // strip as an opaque overlay – it naturally "covers" whichever candidate
            // happens to sit under it at the moment, and `maxScrollX` guarantees the
            // user can still scroll the last candidate fully into the visible area
            // (i.e. to the left of the chevron).
            candidateRects.add(RectF(starts[i], 0f, ends[i], h))
        }
        maxScrollX = max(0f, totalContentWidth - rightLimit)
        maxScrollY = 0f
    }

    private fun layoutGrid() {
        // Row 0 mirrors the single-row layout (composing label + first N candidates,
        // plus the chevron on the right). Candidates that overflow row 0 flow onto
        // subsequent rows which span the full width and have no composing label.
        //
        // In expanded mode the chevron is ALWAYS visible – it's the user's only way back
        // to the single-row layout, so we unconditionally reserve room for it on row 0.
        chevronVisible = true
        val rowH = collapsedRowHeight
        if (composing.isNotEmpty()) {
            val w = labelPaint.measureText(composing) + padH * 2
            composingRect.set(0f, 0f, w, rowH)
        } else {
            composingRect.setEmpty()
        }
        var x = if (composing.isNotEmpty()) composingRect.right else 0f
        var y = 0f
        var row = 0
        val firstRowRight = width - chevronW
        val fullRowRight = width.toFloat()
        for (c in candidates) {
            val tw = max(textPaint.measureText(c), 28f) + padH * 2
            val rightLimit = if (row == 0) firstRowRight else fullRowRight
            if (x + tw > rightLimit) {
                // Wrap to next row
                row++
                y += rowH
                x = 0f
            }
            candidateRects.add(RectF(x, y, x + tw, y + rowH))
            x += tw + gap
        }
        val contentBottom = y + rowH
        maxScrollX = 0f
        maxScrollY = max(0f, contentBottom - height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCandidates()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.save()
        // No clip here: candidates are drawn across their full content width and the
        // chevron button is painted on top at the end of onDraw(), so it acts as an
        // opaque overlay that hides whatever candidate happens to be behind it. This
        // is the "occluder" approach requested by the user and it side-steps the subtle
        // issue of CENTER-aligned glyphs whose advance width exceeds a shrunken cell
        // rect (that trick would make every off-screen candidate's right edge collapse
        // onto `rightLimit`, causing their glyphs to stack on top of each other while
        // scrolling).
        if (!expanded) {
            canvas.translate(-scrollX, 0f)
        } else {
            canvas.translate(0f, -scrollY)
        }

        // Composing area (left)
        if (composing.isNotEmpty()) {
            val w = labelPaint.measureText(composing) + padH * 2
            val rowH = if (expanded) collapsedRowHeight else h
            val rect = RectF(0f, 0f, w, rowH)
            if (downOnComposing) {
                canvas.drawRoundRect(rect, 8f, 8f, highlightPaint)
            }
            val y = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(composing, padH, y, labelPaint)
            // Divider on the right edge of the composing area
            canvas.drawRect(w - 1f, rowH * 0.25f, w, rowH * 0.75f, dividerPaint)
        }

        // Candidates
        for ((i, rect) in candidateRects.withIndex()) {
            if (i == pressedIndex) {
                canvas.drawRoundRect(rect, 8f, 8f, highlightPaint)
            }
            val txt = candidates[i]
            val cx = rect.centerX()
            val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(txt, cx, cy, textPaint)
            if (i < candidateRects.size - 1 && !expanded) {
                val x = rect.right + gap / 2
                canvas.drawRect(x - 0.5f, h * 0.25f, x + 0.5f, h * 0.75f, dividerPaint)
            }
        }
        canvas.restore()

        // Chevron button: always drawn on top of everything, pinned to the right edge.
        // Skipped entirely when not needed (candidates all fit on screen and we're not
        // already in expanded mode) so that the last candidate can use the full width.
        if (chevronVisible) drawChevron(canvas)
    }

    private fun drawChevron(canvas: Canvas) {
        // Opaque background first so the chevron visually occludes any candidate that
        // happens to be scrolled underneath it. Without this fill the candidate glyphs
        // would "bleed" through the chevron region because the candidate strip is
        // drawn with a transparent paint onto the view's background.
        canvas.drawRect(chevronRect, chevronBgPaint)
        // Draw a subtle left-side divider so the chevron feels like its own affordance
        canvas.drawRect(
            chevronRect.left, chevronRect.top + collapsedRowHeight * 0.2f,
            chevronRect.left + 1f, chevronRect.top + collapsedRowHeight * 0.8f,
            dividerPaint
        )
        if (downOnChevron) {
            canvas.drawRoundRect(chevronRect, 8f, 8f, highlightPaint)
        }
        val cx = chevronRect.centerX()
        val cy = chevronRect.centerY()
        val s = collapsedRowHeight * 0.18f
        val path = Path()
        if (!expanded) {
            // ▼
            path.moveTo(cx - s, cy - s * 0.5f)
            path.lineTo(cx, cy + s * 0.5f)
            path.lineTo(cx + s, cy - s * 0.5f)
        } else {
            // ▲
            path.moveTo(cx - s, cy + s * 0.5f)
            path.lineTo(cx, cy - s * 0.5f)
            path.lineTo(cx + s, cy + s * 0.5f)
        }
        canvas.drawPath(path, chevronPaint)
    }

    private var downX = 0f
    private var downY = 0f
    private var downIndex = -1
    // True when the current gesture started in the composing label region (index semantics
    // reuse the sentinel value -1 because composing taps are signalled to the listener as
    // an "out-of-band" commit request).
    private var downOnComposing = false
    private var downOnChevron = false
    private var dragged = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.x
        val rawY = event.y
        // Work out the content-space coordinates. In collapsed mode we scroll on X; in
        // expanded mode we scroll on Y.
        val x = rawX + if (expanded) 0f else scrollX
        val y = rawY + if (expanded) scrollY else 0f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = rawX
                downY = rawY
                dragged = false
                // Only treat taps as chevron hits when the chevron is actually visible;
                // an empty chevronRect means it is disabled and any tap in that area
                // should instead fall through to the candidate hit-test / scroll.
                downOnChevron = chevronVisible && !chevronRect.isEmpty &&
                        chevronRect.contains(rawX, rawY)
                downIndex = if (downOnChevron) -1 else findCandidate(x, y)
                downOnComposing = !downOnChevron && downIndex < 0 && !composingRect.isEmpty &&
                        x >= composingRect.left && x <= composingRect.right &&
                        y >= composingRect.top && y <= composingRect.bottom
                pressedIndex = if (downOnChevron) -1 else downIndex
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = rawX - downX
                val dy = rawY - downY
                if (!dragged && (kotlin.math.abs(dx) > 16f || kotlin.math.abs(dy) > 16f)) {
                    dragged = true
                }
                if (dragged) {
                    if (!expanded) {
                        scrollX = (scrollX - dx).coerceIn(0f, maxScrollX)
                        downX = rawX
                    } else {
                        scrollY = (scrollY - dy).coerceIn(0f, maxScrollY)
                        downY = rawY
                    }
                    pressedIndex = -1
                    downOnComposing = false
                    downOnChevron = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    when {
                        downOnChevron -> {
                            expanded = !expanded
                            scrollY = 0f
                            layoutCandidates()
                            invalidate()
                            listener?.onToggleExpand(expanded)
                        }
                        downIndex >= 0 && downIndex < candidates.size -> {
                            listener?.onPickCandidate(downIndex, candidates[downIndex])
                        }
                        downOnComposing && composing.isNotEmpty() -> {
                            // Sentinel index -1 tells the IME to commit the raw composing text
                            // exactly as shown in the label (Vietnamese quốc ngữ, no conversion).
                            listener?.onPickCandidate(-1, composing)
                        }
                    }
                }
                pressedIndex = -1
                downOnComposing = false
                downOnChevron = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                downOnComposing = false
                downOnChevron = false
                invalidate()
            }
        }
        return true
    }

    private fun findCandidate(xInContent: Float, yInContent: Float): Int {
        for ((i, rect) in candidateRects.withIndex()) {
            if (xInContent >= rect.left && xInContent <= rect.right &&
                yInContent >= rect.top && yInContent <= rect.bottom
            ) return i
        }
        return -1
    }
}
