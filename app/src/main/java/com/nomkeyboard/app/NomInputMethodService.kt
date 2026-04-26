package com.nomkeyboard.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import com.nomkeyboard.app.dict.NgramModel
import com.nomkeyboard.app.dict.NomDictionary
import com.nomkeyboard.app.dict.UserDictionary
import com.nomkeyboard.app.telex.TelexEngine
import com.nomkeyboard.app.ui.CandidateBar
import com.nomkeyboard.app.ui.KeyboardTheme
import com.nomkeyboard.app.ui.KeyboardView

/**
 * Main input-method service for the Nom Keyboard.
 *
 * Workflow:
 *   1. The user types a letter on the KeyboardView -> onChar() feeds it to the Telex engine
 *      and updates the composing buffer.
 *   2. While composing is non-empty, candidates are looked up from [NomDictionary] and
 *      displayed on the [CandidateBar].
 *   3. Tapping a Nom candidate commits it to the text field and clears the composing buffer;
 *      the chosen character's usage counter is bumped for future ordering.
 *   4. Pressing space / enter / punctuation commits the current Vietnamese text first,
 *      then sends the control character.
 *   5. Backspace removes the last character of the composing buffer if non-empty, otherwise
 *      it deletes from the committed text field.
 */
class NomInputMethodService : InputMethodService(), KeyboardView.KeyActionListener,
    CandidateBar.OnCandidatePickListener {

    private lateinit var rootView: LinearLayout
    private lateinit var candidateBar: CandidateBar
    private lateinit var keyboardView: KeyboardView

    /**
     * Still-raw Vietnamese quốc-ngữ being composed. In segment mode this may contain
     * several syllables separated by a single ASCII space (e.g. "quoc gia moi" ->
     * "quốc gia mới"); other modes only ever put one syllable here.
     */
    private var composing: String = ""

    /**
     * Segment-mode: the Nom characters that the user has already confirmed for the
     * *beginning* of the current composition, but which have NOT yet been flushed to
     * the target editor. The full composing span shown with the system underline is
     * always `lockedPrefix + " " + composing` (the space is dropped if either side is
     * empty). Non-segment modes always keep this empty.
     */
    private var lockedPrefix: String = ""

    /**
     * Record of each `onPickCandidate` step that contributed to [lockedPrefix], so that
     * backspace can undo them one at a time. The first entry in the deque is the oldest
     * pick; the last is the most recent.
     *   rawConsumed  – the Vietnamese syllables that this pick replaced
     *   nomText      – the Nom characters that were appended to [lockedPrefix]
     */
    private data class LockedStep(
        val rawConsumed: String,
        val nomText: String,
        /**
         * True when this step came from shorthand-mode picking. For shorthand steps
         * the [rawConsumed] holds the raw letter cluster that was consumed (e.g. the
         * "q" in [q, g]) so backspace can put it back into the composing buffer
         * verbatim.
         */
        val isShorthand: Boolean = false,
        /**
         * Optional user-dictionary learn key. When non-empty, [learnUserPhrases] uses
         * this (instead of [rawConsumed]) as the Vietnamese reading to register the
         * Nom output under. We need this because shorthand picks have a
         * [rawConsumed] that's just a letter cluster (e.g. "vn") which is useless as
         * a reading; the real reading is the bundled-dictionary key the candidate
         * came from (e.g. "việt nam"). Empty for plain (non-shorthand) picks where
         * [rawConsumed] itself is the reading, or for shorthand single-char picks
         * where no meaningful key is available.
         */
        val learnKey: String = "",
    )
    private val lockedHistory: ArrayDeque<LockedStep> = ArrayDeque()

    /**
     * Viết tắt (viet-tat / abbreviated-input) working state. When the user types a run
     * of letters that doesn't look like a partial Vietnamese syllable (e.g. "qg",
     * "nma", "tsao", "nhma"), we greedy-split it into a sequence of syllable prefixes
     * and look up compound words that match segment-by-segment. [shorthandSegments]
     * holds the segmentation; [shorthandActive] gates the viet-tat lookup path.
     *
     * The raw [composing] buffer keeps the user's original letters verbatim so
     * backspacing / continued typing stays intuitive; only the candidate-bar display
     * uses the segmented form with `'` as a separator. When [composing] once again
     * looks like it could be a normal Vietnamese syllable (some key in the ascii
     * single-syllable index starts with it), viet-tat mode switches off and the code
     * falls through to the normal flat lookup.
     *
     * Historical note: the internal field and preference names keep the legacy
     * "shorthand" spelling to avoid invalidating existing user preferences. The
     * user-facing name everywhere else is "viết tắt".
     */
    private var shorthandActive: Boolean = false
    private var shorthandSegments: List<String> = emptyList()

    private var nomTypeface: Typeface? = null

    // Cache of the most-recently computed candidate list so that pressing Space can pick the
    // top candidate as the "best guess". See [onSpace] for the rationale.
    private var currentCandidates: List<String> = emptyList()
    /**
     * Parallel array to [currentCandidates] – the number of Vietnamese syllables that each
     * candidate consumes from [composing]. A value equal to the total syllable count of
     * `composing` means "this candidate covers the entire remaining composing text and
     * picking it should finalise the composition". Values < total are partial matches that
     * keep the remaining syllables live for further picking.
     */
    private var currentCandidateConsumed: IntArray = IntArray(0)
    /**
     * Parallel array to [currentCandidates] used only in shorthand (viết tắt) mode:
     * the bundled-dictionary original Vietnamese key each candidate came from (with
     * diacritics, ascii-only lowercase), so that when the user picks a shorthand
     * candidate we can learn the mapping under the real reading (e.g. "viet nam" ->
     * 越南) instead of the raw shorthand cluster (e.g. "vn"). Empty string for
     * single-char prefix entries where we don't have a dictionary key to key on.
     */
    private var currentShorthandOrigKeys: Array<String> = emptyArray()

    private val recentCounts = HashMap<String, Int>()
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        NomDictionary.ensureLoaded(applicationContext)
        UserDictionary.ensureLoaded(applicationContext)
        NgramModel.ensureLoaded(applicationContext)
        NgramModel.setMaxN(prefs.getString("pref_ngram_n", "3")?.toIntOrNull() ?: 3)
        // Load the bundled Han-Nom font; fail-safe to system font if missing
        nomTypeface = try {
            Typeface.createFromAsset(assets, "fonts/HanNomGothic.ttf")
        } catch (e: Exception) {
            null
        }
        loadRecent()
    }

    override fun onCreateInputView(): View {
        val ctx: Context = this
        rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        candidateBar = CandidateBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.kb_candidate_height)
            )
            // Typeface is assigned in applyTheme() below, according to the user preference.
            listener = this@NomInputMethodService
        }
        keyboardView = KeyboardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            listener = this@NomInputMethodService
        }

        applyTheme()
        // The candidate bar is always part of the layout but starts hidden. We toggle its
        // visibility in [updateComposing] so that it appears only while the user is composing
        // (and the user preference allows it). This matches Gboard: an always-visible strip
        // wastes vertical space when there is nothing to suggest.
        rootView.addView(candidateBar)
        candidateBar.visibility = View.GONE
        rootView.addView(keyboardView)
        return rootView
    }

    private fun applyTheme() {
        val theme = KeyboardTheme.from(applicationContext)
        rootView.setBackgroundColor(theme.bg)
        keyboardView.applyTheme(theme)
        candidateBar.applyTheme(theme)

        keyboardView.setHapticsEnabled(prefs.getBoolean("pref_vibrate", true))
        // Candidate bar font follows user preference: bundled Han-Nom Gothic (default) or
        // the system default. Switching is instant because [CandidateBar.setTypeface] falls
        // back to Typeface.DEFAULT when null is passed.
        val useNomFont = prefs.getBoolean("pref_use_nom_font", true)
        candidateBar.setTypeface(if (useNomFont) nomTypeface else null)

        // Keyboard & candidate-bar font size: user pref stored as one of {"s","m","l","xl"};
        // default "m". The same scale is applied to both surfaces so they always look
        // visually consistent.
        val scale = when (prefs.getString("pref_candidate_text_size", "m")) {
            "s" -> 0.85f
            "l" -> 1.15f
            "xl" -> 1.30f
            else -> 1.0f
        }
        candidateBar.setCandidateTextScale(scale)
        keyboardView.setKeyTextScale(scale)
        // Propagate n-gram N in case the user just changed it.
        NgramModel.setMaxN(prefs.getString("pref_ngram_n", "3")?.toIntOrNull() ?: 3)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetComposing(commit = false)
        NgramModel.resetContext()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetComposing(commit = false)
        applyTheme()
        maybeAutoShift()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd,
            newSelStart, newSelEnd,
            candidatesStart, candidatesEnd
        )
        // Distinguish "we just called setComposingText" from "the user moved the caret".
        //
        // When WE call setComposingText(text, 1), Android replies with a selection update
        // whose caret sits exactly at candidatesEnd (i.e. at the tail of the composing
        // span). The user, on the other hand, can drop the caret anywhere – outside the
        // span, or anywhere strictly inside it. So the reliable "user moved the caret"
        // signal is:
        //   - composing region reported as absent (candidatesStart < 0), OR
        //   - the new caret is NOT exactly at candidatesEnd with an empty selection.
        // Note: we treat "caret at candidatesEnd with selection collapsed" as our own
        // echo; any selection (newSelStart != newSelEnd) or any other position inside the
        // span is treated as a deliberate user action.
        if (hasActiveComposition()) {
            val caretIsOurEcho = candidatesStart >= 0 &&
                    newSelStart == newSelEnd && newSelStart == candidatesEnd
            if (!caretIsOurEcho) {
                // Solidify the current composing text in place: finishComposingText()
                // simply removes the "composing" flag (and the underline) from the span
                // without rewriting its content OR moving the caret. That's exactly what
                // we want – the raw Vietnamese text the user was typing is kept verbatim,
                // and crucially the caret STAYS where the user just tapped. No follow-up
                // setSelection() needed, so a single tap does commit-in-place + move-caret
                // in one shot.
                currentInputConnection?.finishComposingText()
                composing = ""
                lockedPrefix = ""
                lockedHistory.clear()
                currentCandidates = emptyList()
                currentCandidateConsumed = IntArray(0)
                if (::candidateBar.isInitialized) {
                    candidateBar.clear()
                    candidateBar.visibility = View.GONE
                }
                maybeAutoShift()
                return
            }
        }
        // Caret jumped elsewhere (not a composing-text update). Re-evaluate auto-capitalisation
        // because the user may have tapped to position the caret at the start of a new line.
        if (!hasActiveComposition() && candidatesStart < 0) {
            maybeAutoShift()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposing(commit = false)
        NgramModel.resetContext()
        NgramModel.persistNow(applicationContext)
    }

    // ============================ Key callbacks ============================

    override fun onChar(ch: Char) {
        playKeyClickSound()
        if (!ch.isLetter() && ch != '\'' && ch != '-') {
            // Non-letter punctuation: commit whatever composing text we have as plain Vietnamese
            // (so we stay fully compatible with Quốc Ngữ typing) and then insert the raw symbol.
            commitComposing()
            currentInputConnection?.commitText(ch.toString(), 1)
            // Sentence-ending punctuation terminates the n-gram context so that the next
            // sentence starts fresh. Any other symbol (quotes, hyphen etc.) leaves the
            // context alone.
            if (isSentenceTerminator(ch)) NgramModel.resetContext()
            return
        }
        // Shorthand (viết tắt) bypass: once we're in shorthand mode, Telex diacritic /
        // tone transformations are out of scope – the buffer is a non-syllabic letter
        // cluster (e.g. "vn", "tsao") and running a trigger like 's' or 'r' through
        // Telex would mangle it into something nonsensical. Just append the letter
        // verbatim and let [updateComposing] re-split the cluster.
        if (shorthandActive) {
            composing += ch
            updateComposing()
            return
        }
        // The composing buffer may contain multiple space-separated syllables (e.g. "anh "
        // -> typing 'q' yields "anh q"). Telex rules only apply to the last syllable, so we
        // split, transform the tail, then stitch back together.
        val lastSpace = composing.lastIndexOf(' ')
        val head = if (lastSpace >= 0) composing.substring(0, lastSpace + 1) else ""
        val tail = if (lastSpace >= 0) composing.substring(lastSpace + 1) else composing
        // Tone-placement style: "old" = traditional (hóa / thúy), "new" = modern (hoá / thuý).
        // Only affects open-syllable diphthongs; closed syllables are unambiguous.
        val toneStyleOld = prefs.getString("pref_tone_style", "new") == "old"
        val newTail = TelexEngine.apply(tail, ch, toneStyleOld)
        composing = head + newTail
        updateComposing()
    }

    override fun onBackspace() {
        playKeyClickSound()
        // Priority 1: trim the composing (still-Vietnamese) tail character by character.
        if (composing.isNotEmpty()) {
            composing = composing.dropLast(1)
            updateComposing()
            return
        }
        // Priority 2: undo the most-recent locked step (segment mode). The step's raw
        // Vietnamese comes back into `composing` and the corresponding Nom is peeled off
        // `lockedPrefix`. This gives the user the "oops, wrong pick" escape hatch.
        if (lockedHistory.isNotEmpty()) {
            val step = lockedHistory.removeLast()
            // Peel the Nom characters off the tail of lockedPrefix (they must match – they
            // were put there by that very step, in order).
            lockedPrefix = if (lockedPrefix.endsWith(step.nomText)) {
                lockedPrefix.substring(0, lockedPrefix.length - step.nomText.length)
            } else lockedPrefix  // shouldn't happen, but keep the app alive
            composing = step.rawConsumed
            updateComposing()
            return
        }
        // Priority 3: nothing in flight -> delegate to the text field.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEnter() {
        playKeyClickSound()
        commitComposing()
        NgramModel.resetContext()
        val ei = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
        when (action) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT -> currentInputConnection?.performEditorAction(action)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSpace() {
        playKeyClickSound()
        // Three possible behaviours, chosen by the user preference pref_space_behavior:
//   segment        (default) Space inserts a syllable separator
        //                  into the composing buffer so that the user can keep stacking
        //                  syllables (quoc gia moi -> quốc gia mới) and then pick the
        //                  longest matching compound from the candidate bar. A real space
        //                  character is emitted only when there is nothing being composed.
        //   commit_on_empty  Legacy behaviour: keep building a multi-syllable buffer; only
        //                  commit (as plain Vietnamese) when the buffer has no exact Nom
        //                  candidate. See the original long comment below for details.
        //   commit_direct    Always commit immediately: space never tries to pick a Nom
        //                  candidate, it just commits the current composing text as plain
        //                  Vietnamese and emits a real space.
        val mode = prefs.getString("pref_space_behavior", "segment")
        when (mode) {
            "segment" -> onSpaceSegment()
            "commit_direct" -> onSpaceDirect()
            else -> onSpaceCommitOnEmpty()
        }
    }

    /**
     * Segment mode: space is a **syllable separator** while something is being
     * composed, so that the user can keep stacking syllables ("quoc" SPACE "gia" SPACE
     * "moi" → `quốc gia mới`) and then tap the candidate bar to turn the longest
     * matching compound into Nom. A real space character is only emitted when there is
     * absolutely nothing being composed AND no locked prefix in flight.
     *
     * Double-space within the composing buffer (e.g. finished typing and pressed SPACE
     * again) commits the whole composition as-is and emits a literal space.
     */
    private fun onSpaceSegment() {
        if (!hasActiveComposition()) {
            currentInputConnection?.commitText(" ", 1)
            NgramModel.resetContext()
            return
        }
        // Shorthand (viết tắt) in flight: hitting space terminates the shorthand run.
        // The raw letter cluster (e.g. "vn") is NOT a valid Vietnamese syllable so the
        // normal segment-mode policy of "append a separator and wait for more input"
        // doesn't make sense – the user clearly wants to move on. Commit the current
        // composition verbatim (lockedPrefix Nom + raw cluster as-is) and emit a real
        // space. Without this, the user would have to hit space twice (once to exit
        // shorthand, once to actually insert the space) which is the bug reported.
        if (shorthandActive) {
            commitComposing()
            currentInputConnection?.commitText(" ", 1)
            NgramModel.resetContext()
            return
        }
        if (composing.isEmpty()) {
            // lockedPrefix exists, composing is empty -> the user finished picking Nom for
            // every syllable and is now hitting space. Flush the locked Nom and emit a real
            // space after it, mirroring the Vietnamese typing experience.
            commitComposing()
            currentInputConnection?.commitText(" ", 1)
            NgramModel.resetContext()
            return
        }
        if (composing.endsWith(' ')) {
            // Two spaces in a row -> user wants to give up on this composition. Commit what
            // we have (as Vietnamese for the pending part + Nom for the locked part) and
            // emit a literal space.
            commitComposing()
            currentInputConnection?.commitText(" ", 1)
            NgramModel.resetContext()
            return
        }
        // Mid-composition space. If the CURRENT tail segment has no Nom candidates
        // whatsoever (not even single-character hits or prefix completions), there's
        // nothing useful segment mode can do for this segment – so we commit what we
        // have (Nom prefix + current raw tail as Vietnamese) and emit a real space,
        // exactly like commit_on_empty mode. Keeps the UX snappy when the user types
        // an unknown word mid-phrase.
        if (currentCandidates.isEmpty()) {
            commitComposing()
            currentInputConnection?.commitText(" ", 1)
            NgramModel.resetContext()
            return
        }
        // Normal case: append a syllable separator so the next character starts a new
        // syllable. Candidate lookup treats consecutive syllables as a phrase and will
        // surface multi-syllable compounds like "quốc gia" -> 國家.
        composing += " "
        updateComposing()
    }

    /**
     * Legacy "keep composing until there's no candidate" behaviour. Exposed to the user as
     * an explicit preference so the original UX remains available.
     */
    private fun onSpaceCommitOnEmpty() {
        if (composing.isEmpty()) {
            currentInputConnection?.commitText(" ", 1)
            return
        }
        if (composing.endsWith(' ')) {
            // Double-space: commit what we have and emit a literal space.
            val text = composing.trimEnd()
            if (text.isNotEmpty()) {
                currentInputConnection?.commitText(text, 1)
            }
            composing = ""
            currentInputConnection?.commitText(" ", 1)
            updateComposing()
            return
        }
        val trimmed = composing.trim()
        val hasExactCandidate = trimmed.isNotEmpty() && (
                NomDictionary.lookupWord(trimmed).isNotEmpty() ||
                        NomDictionary.lookupSingle(trimmed).isNotEmpty()
                )
        if (!hasExactCandidate) {
            currentInputConnection?.commitText(trimmed, 1)
            composing = ""
            currentInputConnection?.commitText(" ", 1)
            updateComposing()
            return
        }
        composing += " "
        updateComposing()
    }

    /**
     * "No multi-syllable transforms at all" – every space immediately commits the current
     * composing text (as plain Vietnamese) and emits a real space.
     */
    private fun onSpaceDirect() {
        val trimmed = composing.trim()
        if (trimmed.isNotEmpty()) currentInputConnection?.commitText(trimmed, 1)
        composing = ""
        lockedPrefix = ""
        lockedHistory.clear()
        currentCandidates = emptyList()
        currentCandidateConsumed = IntArray(0)
        currentInputConnection?.commitText(" ", 1)
        NgramModel.resetContext()
        updateComposing()
    }

    override fun onSymbol(text: String) {
        commitComposing()
        currentInputConnection?.commitText(text, 1)
        // Most symbols also act as sentence-ish terminators in practice (newline, period,
        // semicolon). We only reset the context on true sentence terminators to avoid
        // over-resetting on things like opening brackets.
        if (text.length == 1 && isSentenceTerminator(text[0])) {
            NgramModel.resetContext()
        }
    }

    override fun onSwitchLanguage() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    // ============================ Candidate selection ============================

    override fun onPickCandidate(index: Int, text: String) {
        // A tap on the composing label (left-most area of the bar) is signalled by index = -1.
        // In that case we commit the raw Vietnamese quốc-ngữ text exactly as the user typed
        // it, without bumping any Nom-character recency counter (it isn't a Nom pick).
        if (index < 0) {
            // The label shows `lockedPrefix + " " + composing`, i.e. the mixed view of
            // already-picked Nom and still-raw Vietnamese. Committing it verbatim is the
            // user's escape hatch: "stop trying to convert, just give me exactly what I
            // see on the screen".
            val raw = composingDisplay().trimEnd()
            if (raw.isNotEmpty()) {
                currentInputConnection?.commitText(raw, 1)
            }
            composing = ""
            lockedPrefix = ""
            lockedHistory.clear()
            currentCandidates = emptyList()
            currentCandidateConsumed = IntArray(0)
            // Committing raw Vietnamese breaks the Nom n-gram context.
            NgramModel.resetContext()
            updateComposing()
            collapseCandidatesIfExpanded()
            return
        }

        val consumed = currentCandidateConsumed.getOrElse(index) { syllableCount(composing) }

        // --------- Shorthand-mode pick ---------
        // In shorthand mode the raw [composing] has NO spaces (e.g. "qg"), so the normal
        // space-based splitter would treat it as one syllable and over-consume. We use
        // [shorthandSegments] (the splitter output) to peel off the right number of
        // consonant-only "segments" per pick.
        if (shorthandActive && shorthandSegments.isNotEmpty()) {
            val segs = shorthandSegments
            val kSh = consumed.coerceIn(1, segs.size)
            // Look up the bundled-dictionary original key for this candidate (if any).
            // This is the "real" Vietnamese reading (e.g. "việt nam" for 越南 picked via
            // "vn", or "bình kiều" for 病嬌 picked via "bkieu") that [learnUserPhrases]
            // will key the mapping on. We preserve the original tones verbatim
            // (lowercase + whitespace-normalised) so the user dictionary records the
            // true Vietnamese spelling instead of a tone-stripped ascii blob like
            // "binh kieu" or "quan tam". UserDictionary itself builds an ascii index
            // internally, so future lookups with or without tones still hit. Empty
            // string when no reading is available (e.g. the candidate came from a
            // single-char prefix hit, not a compound lookup).
            val rawOrigKey = currentShorthandOrigKeys.getOrElse(index) { "" }
            val learnKey = if (rawOrigKey.isEmpty()) "" else
                rawOrigKey.lowercase().replace(Regex("\\s+"), " ").trim()
            // If this pick covers every remaining segment -> FINAL: commit lockedPrefix +
            // text and clear all state. The n-gram model learns the character sequence;
            // [learnUserPhrases] picks up any step that carries a non-empty [learnKey] so
            // shorthand picks contribute the real reading (e.g. "viet nam" -> 越南) to
            // the user dictionary rather than the raw cluster.
            if (kSh >= segs.size) {
                val full = lockedPrefix + text
                val consumedRaw = composing
                lockedHistory.addLast(
                    LockedStep(
                        rawConsumed = consumedRaw,
                        nomText = text,
                        isShorthand = true,
                        learnKey = learnKey,
                    )
                )
                learnUserPhrases(lockedHistory.toList())
                currentInputConnection?.commitText(full, 1)
                observeNgramForCommit(full)
                bumpRecent(text)
                composing = ""
                lockedPrefix = ""
                lockedHistory.clear()
                currentCandidates = emptyList()
                currentCandidateConsumed = IntArray(0)
                currentShorthandOrigKeys = emptyArray()
                shorthandActive = false
                shorthandSegments = emptyList()
                updateComposing()
                collapseCandidatesIfExpanded()
                return
            }
            // Partial pick: eat kSh segments, stitch the remaining segments back into
            // the raw composing buffer (still space-free) and re-evaluate. The step
            // records the consumed letter cluster so backspace can undo it verbatim.
            // We slice [composing] by CHARACTER count (summing segment lengths) rather
            // than by re-joining segment strings so that any casing/typos the user had
            // in the original buffer are preserved verbatim in the undo record.
            val firstKChars = segs.subList(0, kSh).sumOf { it.length }
            val consumedRaw = composing.substring(0, firstKChars.coerceAtMost(composing.length))
            val remainingRaw = if (firstKChars >= composing.length) "" else composing.substring(firstKChars)
            lockedHistory.addLast(
                LockedStep(
                    rawConsumed = consumedRaw,
                    nomText = text,
                    isShorthand = true,
                    learnKey = learnKey,
                )
            )
            lockedPrefix += text
            composing = remainingRaw
            bumpRecent(text)
            updateComposing()
            return
        }

        val syllables = splitSyllables(composing)
        val k = consumed.coerceIn(1, syllables.size.coerceAtLeast(1))

        if (syllables.isEmpty() || k >= syllables.size) {
            // The candidate covers (or overruns) the entire remaining composition -> this is
            // a FINAL pick. Commit the full lockedPrefix + this candidate as one unit and
            // record each Nom character in the n-gram model so it learns the phrase.
            val full = lockedPrefix + text
            // Before we flatten everything, append this final pick to the history so the
            // learner sees the complete step sequence (incl. the just-picked tail). If the
            // composition only had a single, whole-syllable pick there is no multi-step
            // history to learn from, but we still want to register the self-contained
            // mapping so repeated use of a never-before-seen "raw -> Nom" pair gets cached
            // as a user entry.
            val consumedTail = if (syllables.isEmpty()) composing else composing
            // If this pick is the tail of a shorthand run (the previous step was
            // shorthand and the current tail is a single unspaced syllable / letter),
            // recover the full ascii reading of [text] from the reverse single-char
            // index so [learnUserPhrases] can register the combined phrase using real
            // Vietnamese readings rather than the raw letter fragment in [consumedTail]
            // (e.g. "t" -> 心 becomes "tam" -> 心 so the full shorthand run "qt"
            // learns "quan tam" -> 關心 instead of the useless "t" -> 心).
            val tailLearnKey = if (
                lockedHistory.isNotEmpty() && lockedHistory.last().isShorthand &&
                !consumedTail.contains(' ') && text.length == 1
            ) NomDictionary.lookupAsciiReadingForNom(
                text,
                consumedTail,
                lockedHistory.last().learnKey,
            ) else ""
            lockedHistory.addLast(
                LockedStep(
                    rawConsumed = consumedTail,
                    nomText = text,
                    isShorthand = tailLearnKey.isNotEmpty(),
                    learnKey = tailLearnKey,
                )
            )
            learnUserPhrases(lockedHistory.toList())
            currentInputConnection?.commitText(full, 1)
            observeNgramForCommit(full)
            bumpRecent(text)
            composing = ""
            lockedPrefix = ""
            lockedHistory.clear()
            currentCandidates = emptyList()
            currentCandidateConsumed = IntArray(0)
            updateComposing()
            collapseCandidatesIfExpanded()
            return
        }

        // Partial pick: only the first k syllables are consumed. Move them into the
        // lockedPrefix (still *unsubmitted* – the editor will only see the final commit
        // when the user finishes) and keep the rest live for further selection.
        val consumedRaw = syllables.subList(0, k).joinToString(" ")
        val remaining = syllables.subList(k, syllables.size).joinToString(" ")
        // Same shorthand-tail recovery as the final-pick branch above: if the previous
        // step was shorthand and this pick is a single-char single-syllable pick, tag
        // the step with its full ascii reading so the learner sees the real reading.
        val partialLearnKey = if (
            lockedHistory.isNotEmpty() && lockedHistory.last().isShorthand &&
            !consumedRaw.contains(' ') && text.length == 1
        ) NomDictionary.lookupAsciiReadingForNom(
            text,
            consumedRaw,
            lockedHistory.last().learnKey,
        ) else ""
        lockedHistory.addLast(
            LockedStep(
                rawConsumed = consumedRaw,
                nomText = text,
                isShorthand = partialLearnKey.isNotEmpty(),
                learnKey = partialLearnKey,
            )
        )
        lockedPrefix += text
        composing = remaining
        bumpRecent(text)
        updateComposing()
        // In expanded mode we WANT to stay expanded because the user is making multiple
        // picks, so don't collapse here. It will collapse naturally when composing clears.
    }

    override fun onToggleExpand(expanded: Boolean) {
        // Expand: hide the main keyboard so the candidate bar can take over the full IME
        // surface. The bar's own height is bumped to (candidate + keyboard) so the grid
        // layout has room to breathe.
        if (!::keyboardView.isInitialized || !::candidateBar.isInitialized) return
        if (expanded) {
            val kbHeight = keyboardView.height.takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(R.dimen.kb_key_height) * 4
            val barBase = resources.getDimensionPixelSize(R.dimen.kb_candidate_height)
            candidateBar.layoutParams = (candidateBar.layoutParams as LinearLayout.LayoutParams)
                .apply { height = barBase + kbHeight }
            candidateBar.requestLayout()
            keyboardView.visibility = View.GONE
        } else {
            val barBase = resources.getDimensionPixelSize(R.dimen.kb_candidate_height)
            candidateBar.layoutParams = (candidateBar.layoutParams as LinearLayout.LayoutParams)
                .apply { height = barBase }
            candidateBar.requestLayout()
            keyboardView.visibility = View.VISIBLE
        }
    }

    /**
     * Make sure the candidate bar is back to the single-row layout (e.g. after a pick or
     * after composing has been cleared). Mirrors the work done in onToggleExpand(false)
     * so the UI doesn't stay stuck in expanded mode when there's nothing to show.
     */
    private fun collapseCandidatesIfExpanded() {
        if (!::candidateBar.isInitialized) return
        if (!candidateBar.isExpanded()) return
        candidateBar.collapseSilently()
        onToggleExpand(false)
    }

    // ============================ Composing & candidate refresh ============================

    /**
     * @return the string we push to the editor as the composing span. It is the user-visible
     *   "what am I about to commit" – Nom prefix (already picked) followed by the raw
     *   Vietnamese that still awaits conversion.
     */
    private fun composingDisplay(): String {
        return when {
            lockedPrefix.isEmpty() -> composing
            composing.isEmpty() -> lockedPrefix
            else -> lockedPrefix + " " + composing
        }
    }

    /** Convenience predicate: any in-flight composition (locked prefix OR raw tail). */
    private fun hasActiveComposition(): Boolean =
        composing.isNotEmpty() || lockedPrefix.isNotEmpty()

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        val showCandidate = prefs.getBoolean("pref_show_candidates", true)
        if (!hasActiveComposition()) {
            ic.setComposingText("", 1)
            ic.finishComposingText()
            candidateBar.clear()
            currentCandidates = emptyList()
            currentCandidateConsumed = IntArray(0)
            shorthandActive = false
            shorthandSegments = emptyList()
            if (::candidateBar.isInitialized) candidateBar.visibility = View.GONE
            collapseCandidatesIfExpanded()
            return
        }
        // The underline span shown in the editor always uses the raw letters the user typed
        // (never the ' separator we draw on the candidate bar). This keeps the editor
        // content clean and allows copy/paste of the Vietnamese form even before picks.
        ic.setComposingText(composingDisplay(), 1)

        // Shorthand: only kicks in when the current tail has NO space yet (i.e. the user
        // is building up a single "cluster" like "qg"), is entirely consonantal, and can
        // be greedily split into ≥ 2 legal Vietnamese onsets.
        val shorthandEnabled = prefs.getBoolean("pref_shorthand", true)
        val segments = if (shorthandEnabled) tryShorthandSplit(composing) else null
        shorthandActive = segments != null
        shorthandSegments = segments ?: emptyList()

        // Build the candidate-bar preview of the composing text: shorthand uses ' between
        // segments so the user can see the decomposition at a glance.
        val composingForBar = if (shorthandActive) {
            val tail = segments!!.joinToString("'")
            if (lockedPrefix.isEmpty()) tail else lockedPrefix + " " + tail
        } else {
            composingDisplay()
        }
        candidateBar.setComposing(composingForBar)

        // Candidate gathering.
        //   shorthandActive -> use initials-based lookup (compound by initials + first-
        //                      segment single chars for segment-picking).
        //   segmentMode && composing has syllable separators -> existing segment logic.
        //   otherwise                                         -> plain flat lookup, with
        //                                                        optional "single-only"
        //                                                        filter applied for
        //                                                        single-syllable input.
        val segmentMode = prefs.getString("pref_space_behavior", "segment") == "segment"
        if (shorthandActive) {
            val triple = gatherShorthandCandidates(segments!!)
            currentCandidates = triple.first
            currentCandidateConsumed = triple.second
            currentShorthandOrigKeys = triple.third
        } else if (segmentMode && composing.isNotEmpty() && composing.contains(' ')) {
            val (texts, consumed) = gatherSegmentCandidates(composing)
            currentCandidates = texts
            currentCandidateConsumed = consumed
            currentShorthandOrigKeys = emptyArray()
        } else {
            val singleOnly = prefs.getBoolean("pref_single_syl_single_char_only", false)
            val isSingleSyllable = !composing.contains(' ')
            val flat = if (singleOnly && isSingleSyllable) {
                // Single-syllable-only mode: restrict to single-character hits so we
                // never surface compounds like 國家 just because the user typed "qu".
                //
                // Fallbacks in order so that partial typing still gets candidates:
                //   1. exact single-syllable lookup on the current buffer
                //   2. prefix match on the ascii single-syllable index so typing just
                //      "q" surfaces every single-char Nom whose reading begins with q.
                // Step 2 is essential or the bar would be empty until the user finished
                // a full syllable, which felt broken.
                val trimmed = composing.trim()
                val merged = LinkedHashSet<String>()
                merged.addAll(NomDictionary.lookupSingle(trimmed))
                merged.addAll(NomDictionary.lookupSinglePrefix(trimmed))
                merged.toList()
            } else {
                NomDictionary.lookup(composing.trim())
            }
            val ordered = flat.sortedWith(
                compareByDescending<String> { recentCounts.getOrDefault(it, 0) }
                    .thenByDescending { NgramModel.score(it) }
            )
            currentCandidates = ordered
            currentCandidateConsumed = IntArray(ordered.size) { syllableCount(composing) }
            currentShorthandOrigKeys = emptyArray()
        }
        candidateBar.setCandidates(currentCandidates)
        candidateBar.visibility = if (showCandidate) View.VISIBLE else View.GONE
    }

    /**
     * Attempt to split [raw] into a sequence of ≥ 2 "syllable prefix" segments for
     * viết tắt (abbreviated-input) mode.
     *
     * Activation gate: [raw] must contain no whitespace, be at least 2 letters long,
     * contain only ascii letters after diacritic stripping, and NOT be a prefix of
     * any ascii single-syllable key. The last clause is what makes viet-tat safe to
     * run on every keystroke: any input that is still a plausible start of a real
     * Vietnamese syllable (e.g. "qu", "ngh", "an") is left alone, only inputs like
     * "qg", "nma", "tsao", "nhma" – which cannot continue into a real syllable –
     * trigger the split.
     *
     * Split strategy (left-to-right greedy at each position i):
     *   1. Longest ascii single-syllable key K with raw.startsWith(K, i) – swallow K.
     *   2. Otherwise longest multi-letter consonant onset with raw.startsWith(o, i) –
     *      swallow o.
     *   3. Otherwise swallow a single letter.
     * Examples (assuming usual Vietnamese dictionary coverage):
     *   "qg"    -> ["q", "g"]
     *   "tsao"  -> ["t", "sao"]        (sao is a real syllable, swallowed whole)
     *   "nma"   -> ["n", "ma"]
     *   "nhma"  -> ["nh", "ma"]         (nh is a multi-letter onset)
     *   "nc"    -> ["n", "c"]
     *
     * Returns null if the activation gate fails or the split collapses to < 2
     * segments (which would be a single onset – not really abbreviated input).
     */
    private fun tryShorthandSplit(raw: String): List<String>? {
        if (raw.length < 2 || raw.contains(' ')) return null
        val asciiLower = NomDictionary.stripDiacritics(raw.lowercase())
        for (c in asciiLower) {
            if (c !in 'a'..'z') return null
        }
        // Gate: if asciiLower is itself a prefix of any single-syllable ascii key, the
        // user is just mid-syllable on a normal word. [NomDictionary.hasAsciiSinglePrefix]
        // returns true for things like "q" (prefix of "quan", "quoc"…) or "an" (prefix of
        // "anh", "an") so we never hijack those. For "qg", "nma", "tsao" etc. it returns
        // false and we proceed.
        if (NomDictionary.hasAsciiSinglePrefix(asciiLower)) return null

        // Multi-letter consonant onsets, longest-first so "ngh" beats "ng" beats "n".
        // Kept intentionally tight (no "qu"/"gi" – those imply a vowel will follow, which
        // the activation gate has already ruled out).
        val multiOnsets = arrayOf("ngh", "ng", "nh", "ph", "th", "tr", "ch", "gh", "kh")
        val segments = ArrayList<String>()
        var i = 0
        while (i < asciiLower.length) {
            // Step 1: longest ascii-single syllable key at position i.
            var syllableMatch: String? = null
            // Cheap upper bound: a real Vietnamese syllable is ≤ ~7 ascii chars ("nghieng").
            val maxLen = kotlin.math.min(asciiLower.length - i, 8)
            for (len in maxLen downTo 2) {
                val sub = asciiLower.substring(i, i + len)
                if (NomDictionary.isAsciiSingleKey(sub)) {
                    syllableMatch = sub; break
                }
            }
            if (syllableMatch != null) {
                segments.add(syllableMatch); i += syllableMatch.length; continue
            }
            // Step 2: multi-letter consonant onset.
            var onsetMatch: String? = null
            for (o in multiOnsets) {
                if (asciiLower.startsWith(o, i)) { onsetMatch = o; break }
            }
            if (onsetMatch != null) {
                segments.add(onsetMatch); i += onsetMatch.length; continue
            }
            // Step 3: single letter.
            segments.add(asciiLower.substring(i, i + 1)); i += 1
        }
        if (segments.size < 2) return null
        return segments
    }

    /**
     * Build candidates for viết tắt (viet-tat) mode. For a segmentation of length N we surface:
     *   - compound words whose syllables segment-prefix-match segments[0..k-1] for
     *     k = N downTo 2 (see [NomDictionary.lookupWordByVietTat] for the semantics),
     *   - single-character candidates whose ascii syllable starts with segments[0]
     *     (consumed = 1, i.e. "pick just the first segment now, continue picking the
     *     rest afterwards").
     *
     * Partial picks can span multiple segments of different widths, so the [consumed]
     * array carries the *syllable count* of the pick and the matching number of raw
     * characters is recomputed from [shorthandSegments] at pick time via
     * [segmentsCharLen].
     *
     * Duplicates are resolved by keeping the LARGEST consumed value so the user can
     * pick the longest-matching interpretation in one tap. Sorting is the same as
     * segment mode: (consumed desc, recency desc, n-gram score desc).
     */
    private fun gatherShorthandCandidates(
        segments: List<String>
    ): Triple<List<String>, IntArray, Array<String>> {
        if (segments.size < 2) return Triple(emptyList(), IntArray(0), emptyArray())
        // Per-text: best-so-far (consumed, origKey). We keep the origKey of whichever
        // entry produced the current best consumed value so the pick handler can learn
        // the mapping under that reading.
        data class Hit(var consumed: Int, var origKey: String)
        val hits = LinkedHashMap<String, Hit>()
        for (k in segments.size downTo 2) {
            val prefix = segments.subList(0, k)
            for ((origKey, values) in NomDictionary.lookupWordByVietTat(prefix)) {
                for (v in values) {
                    val prev = hits[v]
                    if (prev == null) {
                        hits[v] = Hit(k, origKey)
                    } else if (prev.consumed < k) {
                        prev.consumed = k
                        prev.origKey = origKey
                    }
                }
            }
        }
        // First-segment singles: every Nom character whose ascii syllable starts with
        // segments[0]. Tagged with consumed=1 so picking one keeps the remainder live
        // as a smaller viet-tat cluster (or a single leftover segment if only one
        // segment remains afterwards). The origKey is filled with the full ascii
        // reading of the Nom character that starts with segments[0] (looked up in
        // the reverse single-char index), so that when the user finishes the
        // whole shorthand run the learned user-dict entry uses the real compound
        // reading (e.g. picking 關 then 心 for "qt" learns "quan tam" -> 關心 rather
        // than the useless "t" -> 心 fallback that a missing origKey would produce.
        for (hit in NomDictionary.lookupSinglePrefix(segments[0])) {
            if (!hits.containsKey(hit)) {
                val reading = NomDictionary.lookupAsciiReadingForNom(hit, segments[0])
                hits[hit] = Hit(1, reading)
            }
        }
        val entries = hits.entries.toList().sortedWith(
            compareByDescending<Map.Entry<String, Hit>> { it.value.consumed }
                .thenByDescending { recentCounts.getOrDefault(it.key, 0) }
                .thenByDescending { NgramModel.score(it.key) }
        )
        val texts = ArrayList<String>(entries.size)
        val consumed = IntArray(entries.size)
        val origKeys = Array(entries.size) { "" }
        for ((idx, e) in entries.withIndex()) {
            texts.add(e.key)
            consumed[idx] = e.value.consumed
            origKeys[idx] = e.value.origKey
        }
        return Triple(texts, consumed, origKeys)
    }

    /**
     * Build the candidate list for segment-mode composing.
     *
     * Strategy:
     *   1. Split [raw] into N syllables by ASCII spaces.
     *   2. For k = N downTo 1, try to look up the first k syllables as a compound word
     *      (with AND without spaces so that both "quoc gia" and "quocgia" index entries
     *      hit). Every hit is tagged with consumed=k so the pick handler knows how many
     *      syllables to eat.
     *   3. For k = 1, additionally pull single-character candidates for the first
     *      syllable (standard Nom dictionary lookup).
     *   4. For k = N, additionally pull prefix completions so users that are still typing
     *      a syllable get plausible suggestions. Prefix completions cannot have a reliable
     *      syllable count, so they are tagged with consumed=N which makes them "final
     *      picks": picking one ends the composition entirely.
     *   5. De-duplicate: if the same Nom text appears at multiple k values, keep the
     *      LARGEST consumed so the user can pick the longest-matching interpretation.
     *   6. Sort by (consumed desc, recency desc, n-gram score desc). Longer matches first
     *      prefers matching the longest available phrase first.
     */
    private fun gatherSegmentCandidates(raw: String): Pair<List<String>, IntArray> {
        val syllables = splitSyllables(raw)
        if (syllables.isEmpty()) return Pair(emptyList(), IntArray(0))
        // consumedFor[text] = best (= largest) consumed count we've seen for this text.
        val consumedFor = LinkedHashMap<String, Int>()
        for (k in syllables.size downTo 1) {
            val prefix = syllables.subList(0, k).joinToString(" ")
            if (k > 1) {
                for (hit in NomDictionary.lookupWord(prefix)) {
                    val prev = consumedFor[hit]
                    if (prev == null || prev < k) consumedFor[hit] = k
                }
            } else {
                // Single syllable: use both lookupWord (which also handles 1-syllable
                // compounds in the word index) and lookupSingle for character-level matches.
                for (hit in NomDictionary.lookupWord(prefix)) {
                    val prev = consumedFor[hit]
                    if (prev == null || prev < k) consumedFor[hit] = k
                }
                for (hit in NomDictionary.lookupSingle(prefix)) {
                    val prev = consumedFor[hit]
                    if (prev == null || prev < k) consumedFor[hit] = k
                }
            }
        }
        // Prefix completions for the FULL raw string – useful when the user is still mid-
        // syllable (e.g. typed "anhquo"): we want to propose completions for "anhquoc" etc.
        // Tag them with the full syllable count so picking one is treated as a final pick.
        //
        // We query BOTH the bundled word index and the user dictionary so that user-
        // trained phrases (e.g. "bình kiêu -> 病嬌") surface on any prefix the user has
        // typed so far (e.g. "bình k"), not just when the full key is typed verbatim.
        // User-dict hits come first so recent learnings float to the top.
        val prefixRaw = raw.replace(" ", "")
        for (hit in UserDictionary.lookupPrefix(raw, 24)) {
            if (!consumedFor.containsKey(hit)) consumedFor[hit] = syllables.size
        }
        for (hit in NomDictionary.lookupPrefix(prefixRaw, 24)) {
            // Don't let prefix completions override better (longer-exact) consumed values.
            if (!consumedFor.containsKey(hit)) consumedFor[hit] = syllables.size
        }

        val entries = consumedFor.entries.toList().sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { recentCounts.getOrDefault(it.key, 0) }
                .thenByDescending { NgramModel.score(it.key) }
        )
        val texts = ArrayList<String>(entries.size)
        val consumed = IntArray(entries.size)
        for ((idx, e) in entries.withIndex()) {
            texts.add(e.key)
            consumed[idx] = e.value
        }
        return Pair(texts, consumed)
    }

    /**
     * Record every Nom character of [commitString] into the n-gram model in order. We go
     * character-by-character (Unicode code points, to stay correct on surrogate-pair
     * ideographs) so that the model sees the true commit sequence, not just the final
     * compound as a single token.
     */
    private fun observeNgramForCommit(commitString: String) {
        var i = 0
        while (i < commitString.length) {
            val cp = commitString.codePointAt(i)
            val ch = String(Character.toChars(cp))
            // Skip whitespace between Nom tokens – the n-gram context is about glyphs,
            // not about the incidental ASCII spaces we insert into the visible composing
            // span for legibility.
            if (ch.isNotBlank()) NgramModel.observe(applicationContext, ch)
            i += Character.charCount(cp)
        }
    }

    /**
     * Register every contiguous subsequence of [steps] (of length ≥ 2, capped by the
     * current n-gram window) as a new entry in [UserDictionary]. Plus the full-length
     * subsequence, which IS always recorded regardless of the n-gram cap so that users
     * get their "exact phrase" back verbatim next time.
     *
     * Concretely, if the user picked three segments [A=國家, B=新, C=共和] corresponding
     * to ["quoc gia", "moi", "cong hoa"], we register:
     *   - "quoc gia moi cong hoa"        -> 國家新共和    (the whole phrase, always)
     *   - "quoc gia moi"                 -> 國家新        (contiguous 2- and 3-step subs)
     *   - "moi cong hoa"                 -> 新共和
     *   - (size-1 steps are skipped – they're either already in the bundled dictionary
     *      or will be strengthened by the n-gram model on next commit)
     *
     * Subsequences of size ≥ n-gram-N are skipped (except the full phrase) to avoid
     * ballooning the user dictionary with combinations the n-gram model will never
     * score anyway.
     *
     * Merging policy: if the key already exists in [UserDictionary], the new Nom value
     * is PREPENDED (so recency wins on ties) and the previous values are kept.
     */
    private fun learnUserPhrases(steps: List<LockedStep>) {
        if (steps.isEmpty()) return
        // Respect the "auto-learn after segment-mode picks" preference. When the
        // user disables it, every path that would have registered a new phrase
        // into the user dictionary (segment picks, shorthand compound picks,
        // shorthand tails …) becomes a no-op. The n-gram frequency model is
        // untouched; only user-dictionary entries are skipped here.
        if (!prefs.getBoolean("pref_auto_learn_segment", true)) return
        // Drop steps that have no usable reading: a shorthand step is usable only when
        // it carries a non-empty [learnKey] (set by the pick handler when the
        // candidate came from a bundled-dictionary compound, e.g. "vn" -> "việt nam");
        // a non-shorthand step is usable whenever [rawConsumed] is non-empty. This lets
        // shorthand compound picks contribute the REAL Vietnamese reading to the user
        // dictionary (so "viet nam" later hits 越南), while still skipping the useless
        // single-char shorthand picks that have no reading.
        val cleanSteps = steps.filter {
            val reading = if (it.learnKey.isNotEmpty()) it.learnKey else it.rawConsumed
            reading.isNotEmpty() && !(it.isShorthand && it.learnKey.isEmpty())
        }
        if (cleanSteps.isEmpty()) return
        val ctx = applicationContext
        // Make sure the user dictionary is loaded before we attempt to read it for merging.
        UserDictionary.ensureLoaded(ctx)
        val existing = UserDictionary.allEntries().associate { it.first to it.second }
        val ngramN = prefs.getString("pref_ngram_n", "3")?.toIntOrNull()?.coerceIn(1, 6) ?: 3
        // Collect subs to register. A LinkedHashMap keeps insertion order so longer /
        // more-informative phrases appear first in the candidate list when the user types
        // them again.
        data class Sub(val key: String, val nom: String)
        val subs = ArrayList<Sub>()
        // Per-step reading: learnKey wins when set (shorthand), otherwise rawConsumed.
        fun readingOf(step: LockedStep): String =
            if (step.learnKey.isNotEmpty()) step.learnKey else step.rawConsumed
        // Always include the full phrase first.
        val fullKey = cleanSteps.joinToString(" ") { readingOf(it).trim() }
            .replace(Regex("\\s+"), " ").trim().lowercase()
        val fullNom = cleanSteps.joinToString("") { it.nomText }
        if (fullKey.isNotEmpty() && fullNom.isNotEmpty()) subs.add(Sub(fullKey, fullNom))
        // Then every shorter contiguous subsequence of length 2..min(steps.size-1, ngramN).
        // Length 1 is skipped (single picks are bundled-dict hits; no point duplicating).
        val maxSubLen = kotlin.math.min(cleanSteps.size - 1, ngramN)
        for (len in maxSubLen downTo 2) {
            for (start in 0..cleanSteps.size - len) {
                // Skip the [0, steps.size) range – already added above as full phrase.
                if (start == 0 && len == cleanSteps.size) continue
                val segment = cleanSteps.subList(start, start + len)
                val key = segment.joinToString(" ") { readingOf(it).trim() }
                    .replace(Regex("\\s+"), " ").trim().lowercase()
                val nom = segment.joinToString("") { it.nomText }
                if (key.isEmpty() || nom.isEmpty()) continue
                subs.add(Sub(key, nom))
            }
        }
        // Deduplicate by key (keep first occurrence = longest / earliest / most general).
        val seen = HashSet<String>()
        for (s in subs) {
            if (!seen.add(s.key)) continue
            val prev = existing[s.key].orEmpty()
            val merged = LinkedHashSet<String>()
            // New Nom wins the top slot so just-used phrases surface first next time.
            merged.add(s.nom)
            merged.addAll(prev)
            UserDictionary.putEntry(ctx, s.key, merged.toList())
        }
    }

    private fun splitSyllables(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(' ').filter { it.isNotEmpty() }
    }

    private fun syllableCount(raw: String): Int {
        val n = splitSyllables(raw).size
        return if (n == 0) 1 else n
    }

    private fun commitComposing() {
        if (!hasActiveComposition()) return
        // Final shape: Nom prefix (already-picked) + raw Vietnamese tail.
        val text = composingDisplay().trimEnd()
        if (text.isNotEmpty()) {
            currentInputConnection?.commitText(text, 1)
            // Learn the Nom prefix only – the raw Vietnamese tail isn't Nom and shouldn't
            // pollute the n-gram model.
            if (lockedPrefix.isNotEmpty()) observeNgramForCommit(lockedPrefix)
            // Only register user-dictionary phrases when the whole composition was
            // resolved into Nom (composing tail is empty). A non-empty tail means the user
            // aborted mid-conversion (e.g. pressed Enter / typed punctuation before
            // finishing) and we'd otherwise lock in half-baked mappings.
            if (composing.isEmpty() && lockedHistory.isNotEmpty()) {
                learnUserPhrases(lockedHistory.toList())
            }
        }
        composing = ""
        lockedPrefix = ""
        lockedHistory.clear()
        currentCandidates = emptyList()
        currentCandidateConsumed = IntArray(0)
        currentShorthandOrigKeys = emptyArray()
        shorthandActive = false
        shorthandSegments = emptyList()
        candidateBar.clear()
        currentInputConnection?.finishComposingText()
        maybeAutoShift()
    }

    private fun resetComposing(commit: Boolean) {
        if (commit) commitComposing()
        composing = ""
        lockedPrefix = ""
        lockedHistory.clear()
        currentCandidates = emptyList()
        currentCandidateConsumed = IntArray(0)
        currentShorthandOrigKeys = emptyArray()
        shorthandActive = false
        shorthandSegments = emptyList()
        if (::candidateBar.isInitialized) candidateBar.clear()
        currentInputConnection?.finishComposingText()
    }

    // ============================ Misc helpers ============================

    /**
     * Turn on one-shot Shift whenever the caret is at the start of a "sentence", provided the
     * user-preference [pref_auto_caps] is enabled and the current editor doesn't suppress
     * auto-capitalisation (passwords, URLs, email addresses etc.).
     *
     * A caret is considered "at the start of a sentence" when:
     *   - There is no text before it, OR
     *   - The preceding non-space character is one of  . ! ? 。 ！ ？  or a newline.
     *
     * We consult the InputConnection to peek at the text immediately before the cursor. If the
     * connection refuses to return anything (some password fields do that) we just leave
     * Shift alone – a silent no-op is less annoying than a false positive.
     */
    private fun maybeAutoShift() {
        if (!::keyboardView.isInitialized) return
        if (!prefs.getBoolean("pref_auto_caps", false)) return
        val ei = currentInputEditorInfo ?: return
        // Respect the editor's own opinion on auto-capitalisation.
        val klass = ei.inputType and EditorInfo.TYPE_MASK_CLASS
        val variation = ei.inputType and EditorInfo.TYPE_MASK_VARIATION
        if (klass != EditorInfo.TYPE_CLASS_TEXT) return
        when (variation) {
            EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_URI,
            EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return
        }
        val ic = currentInputConnection ?: return
        // Grab up to 64 chars before the cursor – enough to find the last sentence break
        // without costing much.
        val before: CharSequence = ic.getTextBeforeCursor(64, 0) ?: ""
        if (isStartOfSentence(before)) {
            keyboardView.setShiftTemporary()
        }
    }

    /**
     * @return true iff [before] is empty or ends (after trimming trailing spaces) with a
     *   sentence-terminating punctuation / newline.
     */
    private fun isStartOfSentence(before: CharSequence): Boolean {
        // Walk backwards past ASCII spaces, tabs and the fullwidth ideographic space.
        var i = before.length - 1
        while (i >= 0) {
            val c = before[i]
            if (c == ' ' || c == '\t' || c == '\u3000') i-- else break
        }
        if (i < 0) return true
        val c = before[i]
        return c == '.' || c == '!' || c == '?' ||
                c == '\u3002' /* 。 */ || c == '\uff01' /* ！ */ || c == '\uff1f' /* ？ */ ||
                c == '\n' || c == '\r'
    }

    private fun isSentenceTerminator(c: Char): Boolean {
        return c == '.' || c == '!' || c == '?' ||
                c == '\u3002' /* 。 */ || c == '\uff01' /* ！ */ || c == '\uff1f' /* ？ */ ||
                c == ',' || c == ';' || c == ':' ||
                c == '\uff0c' /* ， */ || c == '\uff1b' /* ； */ || c == '\uff1a' /* ： */ ||
                c == '\n' || c == '\r'
    }

    private fun playKeyClickSound() {
        if (!prefs.getBoolean("pref_sound", false)) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    private fun bumpRecent(text: String) {
        val n = recentCounts.getOrDefault(text, 0) + 1
        recentCounts[text] = n
        if (recentCounts.size > 500) {
            // Keep the top-400 most frequent entries to bound memory/prefs size
            val kept = recentCounts.entries.sortedByDescending { it.value }.take(400)
            recentCounts.clear()
            for ((k, v) in kept) recentCounts[k] = v
        }
        // Persist asynchronously
        prefs.edit().apply {
            putString(PREF_RECENT, recentCounts.entries.joinToString("|") { "${it.key}:${it.value}" })
            apply()
        }
    }

    private fun loadRecent() {
        val s = prefs.getString(PREF_RECENT, null) ?: return
        recentCounts.clear()
        for (pair in s.split("|")) {
            val idx = pair.lastIndexOf(":")
            if (idx > 0) {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1).toIntOrNull() ?: continue
                if (k.isNotEmpty()) recentCounts[k] = v
            }
        }
    }

    companion object {
        const val PREF_RECENT = "pref_recent_map"
    }
}
