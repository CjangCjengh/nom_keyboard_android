package com.nomkeyboard.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nomkeyboard.app.R

/**
 * Self-drawn Gboard-style QWERTY keyboard (Vietnamese layout, which is identical to the
 * English QWERTY layout in terms of key placement).
 *
 * Features:
 *   - Four rows of QWERTY letters plus a symbol page (?123 and =\<).
 *     The candidate bar is rendered by a separate [CandidateBar] view above this one.
 *   - Shift: single tap for temporary uppercase, double tap for caps-lock.
 *   - Long-press on backspace repeats the delete action.
 *   - All events are delivered to the owner via [KeyActionListener].
 *
 * No XML layout is used; keys are fully drawn for a smoother Gboard-like feel.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ----------- Public callback -----------
    interface KeyActionListener {
        fun onChar(ch: Char)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
        fun onSymbol(text: String)          // any other symbol
        fun onSwitchLanguage()              // globe key
    }

    var listener: KeyActionListener? = null

    // ----------- Theme -----------
    private var theme: KeyboardTheme = KeyboardTheme.light(context)
    fun applyTheme(t: KeyboardTheme) {
        theme = t
        keyBgPaint.color = t.key
        keyFuncBgPaint.color = t.keyFunc
        actionBgPaint.color = t.action
        keyPressPaint.color = t.press
        textPaint.color = t.text
        textFuncPaint.color = t.textFunc
        setBackgroundColor(t.bg)
        invalidate()
    }

    // ----------- Paints -----------
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyFuncBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyPressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val textFuncPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val actionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Paths use the same 24dp viewport so the controls align regardless of font fallback.
    private val icons = mapOf(
        KeyCode.SHIFT to "M12 3l9 10h-5v7H8v-7H3z M12 6l-4.5 5H10v7h4v-7h2.5z",
        KeyCode.BACKSPACE to "M8 4h14v16H8l-7-8z M9 6l-5.4 6L9 18h11V6z M10 9l1.4-1.4L14 10.2l2.6-2.6L18 9l-2.6 2.6L18 14.2l-1.4 1.4-2.6-2.6-2.6 2.6-1.4-1.4 2.6-2.6z",
        KeyCode.ENTER to "M20 6v6a2 2 0 0 1-2 2H6.8l3.6 3.6L9 19l-6-6 6-6 1.4 1.4L6.8 12H18V6z",
        KeyCode.LANGUAGE to "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20 M4.3 11a8 8 0 0 1 4.1-6a17 17 0 0 0-1.3 6z M9.1 11c.3-3.2 1.6-5.9 2.9-7 1.3 1.1 2.6 3.8 2.9 7z M16.9 11a17 17 0 0 0-1.3-6a8 8 0 0 1 4.1 6z M4.3 13h2.8a17 17 0 0 0 1.3 6a8 8 0 0 1-4.1-6z M9.1 13h5.8c-.3 3.2-1.6 5.9-2.9 7-1.3-1.1-2.6-3.8-2.9-7z M16.9 13h2.8a8 8 0 0 1-4.1 6a17 17 0 0 0 1.3-6z",
    ).mapValues { (_, data) -> PathParser.createPathFromPathData(data).apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
    } }
    private val shiftFilled = PathParser.createPathFromPathData("M12 3l9 10h-5v7H8v-7H3z")

    // ----------- Dimensions -----------
    private val keyHeight = resources.getDimension(R.dimen.kb_key_height)
    private val keyGap = resources.getDimension(R.dimen.kb_key_gap)
    private val rowGap = resources.getDimension(R.dimen.kb_row_gap)
    private val keyRadius = resources.getDimension(R.dimen.kb_key_radius)
    // Base font sizes (px). Stored as mutable properties so the IME can apply a user-chosen
    // scale to both the main letter glyphs and the secondary labels (?123, ABC, space, …).
    // Whenever we rescale, we also recompute baseKeyTextSize / baseKeyLabelTextSize so the
    // scale is always applied on top of the original design values (no drift).
    private val baseKeyTextSize = resources.getDimension(R.dimen.kb_key_text_size)
    private val baseKeyLabelTextSize = resources.getDimension(R.dimen.kb_key_label_text_size)
    private var keyTextSize = baseKeyTextSize
    private var keyLabelTextSize = baseKeyLabelTextSize
    private val sidePad = resources.getDimension(R.dimen.kb_side_pad)
    private val topPad = resources.getDimension(R.dimen.kb_top_pad)
    private val bottomPad = resources.getDimension(R.dimen.kb_bottom_pad)

    // ----------- State -----------
    // 0: letters page ; 1: symbols page ?123 ; 2: symbols page =\<
    private var page = 0

    // shift: 0 off, 1 temporary (next letter only), 2 caps-lock
    private var shift = 0

    private var hapticsEnabled: Boolean = true
    fun setHapticsEnabled(v: Boolean) { hapticsEnabled = v }

    /**
     * Apply a multiplicative scale to the key glyph / secondary-label font sizes. 1.0 = the
     * resource default. Called from the IME whenever the user changes the "keyboard font size"
     * preference, so the main QWERTY keys and candidate bar grow / shrink together.
     */
    fun setKeyTextScale(scale: Float) {
        val s = scale.coerceIn(0.6f, 2.0f)
        keyTextSize = baseKeyTextSize * s
        keyLabelTextSize = baseKeyLabelTextSize * s
        invalidate()
    }

    // ----------- Key model -----------
    /** Metadata for a single key. */
    private data class Key(
        val label: String,
        val code: KeyCode,
        val weight: Float = 1f,     // width weight within the row
        val secondary: String? = null,  // label for long-press popup (not drawn in MVP)
        val func: Boolean = false,      // function key (different background)
    )

    private enum class KeyCode {
        CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOL, SYMBOL2, LETTER,
        COMMA, PERIOD, LANGUAGE, QUESTION, EMOJI
    }

    private fun bottomRow(letters: Boolean) = listOf(
        if (letters) Key("?123", KeyCode.SYMBOL, 1.45f, func = true)
        else Key("ABC", KeyCode.LETTER, 1.45f, func = true),
        Key(",", KeyCode.COMMA),
        Key("", KeyCode.LANGUAGE, func = true),
        Key("Chữ Nôm", KeyCode.SPACE, 4.1f),
        Key(".", KeyCode.PERIOD),
        Key("", KeyCode.ENTER, 1.45f, func = true),
    )

    // Letters page
    private val lettersRows: List<List<Key>> = listOf(
        "qwertyuiop".map { Key(it.toString(), KeyCode.CHAR) },
        "asdfghjkl".map { Key(it.toString(), KeyCode.CHAR) },
        listOf(Key("⇧", KeyCode.SHIFT, weight = 1.4f, func = true)) +
                "zxcvbnm".map { Key(it.toString(), KeyCode.CHAR) } +
                listOf(Key("⌫", KeyCode.BACKSPACE, weight = 1.4f, func = true)),
        bottomRow(true),
    )

    // Symbols page 1
    private val symbolsRows: List<List<Key>> = listOf(
        "1234567890".map { Key(it.toString(), KeyCode.CHAR) },
        "@#\$_&-+()/*".map { Key(it.toString(), KeyCode.CHAR) },
        listOf(Key("=\\<", KeyCode.SYMBOL2, weight = 1.4f, func = true)) +
                "\"':;!?".map { Key(it.toString(), KeyCode.CHAR) } +
                listOf(Key("⌫", KeyCode.BACKSPACE, weight = 1.4f, func = true)),
        bottomRow(false),
    )

    // Symbols page 2
    private val symbols2Rows: List<List<Key>> = listOf(
        "~`|•√π÷×¶∆".map { Key(it.toString(), KeyCode.CHAR) },
        "£¥€¢^°=¤".map { Key(it.toString(), KeyCode.CHAR) },
        listOf(Key("?123", KeyCode.SYMBOL, weight = 1.4f, func = true)) +
                "\\/<>{}[]".map { Key(it.toString(), KeyCode.CHAR) } +
                listOf(Key("⌫", KeyCode.BACKSPACE, weight = 1.4f, func = true)),
        bottomRow(false),
    )

    private fun currentRows(): List<List<Key>> = when (page) {
        1 -> symbolsRows
        2 -> symbols2Rows
        else -> lettersRows
    }

    // ----------- Layout cache -----------
    private data class KeyRect(val key: Key, val rect: RectF)
    private val keyRects: MutableList<KeyRect> = ArrayList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = currentRows().size
        val h = (topPad + bottomPad + keyHeight * rows + rowGap * (rows - 1)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        keyRects.clear()
        val rows = currentRows()
        val w = width.toFloat()
        if (w <= 0) return
        val usableWidth = w - sidePad * 2
        var y = topPad
        for ((rowIndex, row) in rows.withIndex()) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val totalGap = keyGap * (row.size - 1)
            val inset = if (page == 0 && rowIndex == 1) (usableWidth + keyGap) / 20f else 0f
            val unit = (usableWidth - 2 * inset - totalGap) / totalWeight
            var x = sidePad + inset
            for (key in row) {
                val keyW = unit * key.weight
                val rect = RectF(x, y, x + keyW, y + keyHeight)
                keyRects.add(KeyRect(key, rect))
                x += keyW + keyGap
            }
            y += keyHeight + rowGap
        }
    }

    // ----------- Drawing -----------
    override fun onDraw(canvas: Canvas) {
        for (kr in keyRects) {
            drawKey(canvas, kr)
        }
    }

    private fun drawKey(canvas: Canvas, kr: KeyRect) {
        val k = kr.key
        val pressed = pressedKey === kr
        val bgPaint = when {
            pressed -> keyPressPaint
            k.code == KeyCode.ENTER -> actionBgPaint
            k.func -> keyFuncBgPaint
            else -> keyBgPaint
        }
        val pill = k.code in actionKeys
        val radius = if (pill) kr.rect.height() / 2 else keyRadius
        canvas.drawRoundRect(kr.rect, radius, radius, bgPaint)

        val cx = kr.rect.centerX()
        val cy = kr.rect.centerY()
        val icon = if (k.code == KeyCode.SHIFT && shift > 0) shiftFilled else icons[k.code]
        if (icon != null) {
            iconPaint.color = theme.textFunc
            canvas.save()
            val scale = (keyTextSize / 24f).coerceAtMost(kr.rect.width() / 30f)
            canvas.translate(cx - 12 * scale, cy - 12 * scale)
            canvas.scale(scale, scale)
            canvas.drawPath(icon, iconPaint)
            if (k.code == KeyCode.SHIFT && shift == 2) canvas.drawRect(8f, 21f, 16f, 23f, iconPaint)
            canvas.restore()
            return
        }
        val paint = if (k.func || k.code == KeyCode.SPACE) textFuncPaint else textPaint
        paint.textSize = if (k.func || k.code == KeyCode.SPACE) keyLabelTextSize else keyTextSize
        val label = if (k.code == KeyCode.CHAR && page == 0 && shift > 0) k.label.uppercase() else k.label
        canvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
    }

    private val actionKeys = setOf(KeyCode.ENTER, KeyCode.SYMBOL, KeyCode.LETTER, KeyCode.SYMBOL2)

    // ----------- Touch handling -----------
    private var pressedKey: KeyRect? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var repeated = false
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (pressedKey?.key?.code != KeyCode.BACKSPACE) return
            repeated = true
            listener?.onBackspace()
            handler.postDelayed(this, 60)
        }
    }

    private fun press(key: KeyRect?) {
        handler.removeCallbacks(repeatDelete)
        repeated = false
        pressedKey = key
        if (key?.key?.code == KeyCode.BACKSPACE) handler.postDelayed(repeatDelete, 400)
        invalidate()
    }

    private fun release(commit: Boolean) {
        val key = pressedKey?.key
        val dispatch = commit && !repeated
        pointer = MotionEvent.INVALID_POINTER_ID
        press(null)
        if (dispatch && key != null) handleKey(key)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Rollover typing: finish the previous letter before starting the next.
                release(event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                val i = event.actionIndex
                pointer = event.getPointerId(i)
                press(findKey(event.getX(i), event.getY(i)))
                if (pressedKey != null && hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(pointer)
                if (i >= 0) {
                    val key = findKey(event.getX(i), event.getY(i))
                    if (key !== pressedKey) press(key)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) release(true)
            }
            MotionEvent.ACTION_CANCEL -> release(false)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        release(false)
        super.onDetachedFromWindow()
    }

    /** Find the key under (x, y); if none, return the closest key on the same row within tolerance. */
    private fun findKey(x: Float, y: Float): KeyRect? {
        var best: KeyRect? = null
        var bestDx = Float.MAX_VALUE
        for (kr in keyRects) {
            if (kr.rect.contains(x, y)) return kr
            if (y >= kr.rect.top - rowGap / 2 && y < kr.rect.bottom + rowGap / 2) {
                val dx = when {
                    x < kr.rect.left -> kr.rect.left - x
                    x > kr.rect.right -> x - kr.rect.right
                    else -> 0f
                }
                if (dx < bestDx) {
                    best = kr
                    bestDx = dx
                }
            }
        }
        // Only accept the approximate match when it is within ~3 gaps
        return if (best != null && bestDx < keyGap * 3) best else null
    }

    // ----------- Key action dispatch -----------
    private fun handleKey(k: Key) {
        when (k.code) {
            KeyCode.CHAR -> {
                val raw = k.label[0]
                val ch = if (page == 0) {
                    if (shift > 0) raw.uppercaseChar() else raw
                } else raw
                listener?.onChar(ch)
                if (shift == 1) {
                    shift = 0
                    invalidate()
                }
            }
            KeyCode.SHIFT -> {
                shift = when (shift) {
                    0 -> 1
                    1 -> 2
                    else -> 0
                }
                invalidate()
            }
            KeyCode.BACKSPACE -> listener?.onBackspace()
            KeyCode.ENTER -> listener?.onEnter()
            KeyCode.SPACE -> {
                listener?.onSpace()
                if (shift == 1) {
                    shift = 0
                    invalidate()
                }
            }
            KeyCode.SYMBOL -> switchPage(1)
            KeyCode.SYMBOL2 -> switchPage(2)
            KeyCode.LETTER -> switchPage(0)
            KeyCode.COMMA -> listener?.onChar(',')
            KeyCode.PERIOD -> listener?.onChar('.')
            KeyCode.LANGUAGE -> listener?.onSwitchLanguage()
            else -> { /* ignore */ }
        }
    }

    fun resetShift() {
        if (shift != 0) {
            shift = 0
            invalidate()
        }
    }

    /**
     * Request a one-shot temporary Shift (single next letter uppercased).
     *
     * This is called by the input-method service when auto-capitalisation detects that the
     * caret is at the start of a sentence. We only override when Shift is currently OFF
     * (state 0) – if the user already engaged a manual Shift or Caps-Lock we leave their
     * explicit choice untouched.
     */
    fun setShiftTemporary() {
        if (shift == 0) {
            shift = 1
            invalidate()
        }
    }

    /**
     * Switch between the letters / ?123 / =\< pages.
     *
     * We explicitly rebuild the key layout and request a re-measure, because the new page may
     * contain a different number of rows (all three pages happen to have four rows here, but we
     * must still refresh `keyRects` so that the newly drawn keys are actually hit-testable).
     * Just calling `requestLayout()` is not enough: when the measured size does not change,
     * `onSizeChanged()` is never invoked and the old `keyRects` linger from the previous page,
     * causing tapped keys on the new page to do nothing.
     */
    private fun switchPage(newPage: Int) {
        if (page == newPage) return
        page = newPage
        pressedKey = null
        layoutKeys()
        requestLayout()
        invalidate()
    }
}
