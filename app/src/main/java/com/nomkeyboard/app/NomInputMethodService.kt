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
    private data class LockedStep(val rawConsumed: String, val nomText: String)
    private val lockedHistory: ArrayDeque<LockedStep> = ArrayDeque()

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
            lockedHistory.addLast(LockedStep(rawConsumed = consumedTail, nomText = text))
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
        lockedHistory.addLast(LockedStep(rawConsumed = consumedRaw, nomText = text))
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
            if (::candidateBar.isInitialized) candidateBar.visibility = View.GONE
            collapseCandidatesIfExpanded()
            return
        }
        ic.setComposingText(composingDisplay(), 1)
        candidateBar.setComposing(composingDisplay())

        // Candidate gathering. In segment mode, "composing" may be several syllables and
        // we want to expose compound (multi-syllable) matches with their consumed-count
        // metadata so that onPickCandidate can peel the right prefix. In the other two
        // modes, composing is always a single syllable and we fall back to the original
        // flat lookup.
        val segmentMode = prefs.getString("pref_space_behavior", "segment") == "segment"
        if (segmentMode && composing.isNotEmpty()) {
            val (texts, consumed) = gatherSegmentCandidates(composing)
            currentCandidates = texts
            currentCandidateConsumed = consumed
        } else {
            val flat = NomDictionary.lookup(composing.trim())
            val ordered = flat.sortedWith(
                compareByDescending<String> { recentCounts.getOrDefault(it, 0) }
                    .thenByDescending { NgramModel.score(it) }
            )
            currentCandidates = ordered
            currentCandidateConsumed = IntArray(ordered.size) { syllableCount(composing) }
        }
        candidateBar.setCandidates(currentCandidates)
        candidateBar.visibility = if (showCandidate) View.VISIBLE else View.GONE
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
        // Always include the full phrase first.
        val fullKey = steps.joinToString(" ") { it.rawConsumed.trim() }
            .replace(Regex("\\s+"), " ").trim().lowercase()
        val fullNom = steps.joinToString("") { it.nomText }
        if (fullKey.isNotEmpty() && fullNom.isNotEmpty()) subs.add(Sub(fullKey, fullNom))
        // Then every shorter contiguous subsequence of length 2..min(steps.size-1, ngramN).
        // Length 1 is skipped (single picks are bundled-dict hits; no point duplicating).
        val maxSubLen = kotlin.math.min(steps.size - 1, ngramN)
        for (len in maxSubLen downTo 2) {
            for (start in 0..steps.size - len) {
                // Skip the [0, steps.size) range – already added above as full phrase.
                if (start == 0 && len == steps.size) continue
                val segment = steps.subList(start, start + len)
                val key = segment.joinToString(" ") { it.rawConsumed.trim() }
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
