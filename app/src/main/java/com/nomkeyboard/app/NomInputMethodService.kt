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

    private var composing: String = ""
    private var nomTypeface: Typeface? = null

    // Cache of the most-recently computed candidate list so that pressing Space can pick the
    // top candidate as the "best guess". See [onSpace] for the rationale.
    private var currentCandidates: List<String> = emptyList()

    private val recentCounts = HashMap<String, Int>()
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        NomDictionary.ensureLoaded(applicationContext)
        UserDictionary.ensureLoaded(applicationContext)
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
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetComposing(commit = false)
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
        // Caret jumped elsewhere (not a composing-text update). Re-evaluate auto-capitalisation
        // because the user may have tapped to position the caret at the start of a new line.
        if (composing.isEmpty() && candidatesStart < 0) {
            maybeAutoShift()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposing(commit = false)
    }

    // ============================ Key callbacks ============================

    override fun onChar(ch: Char) {
        playKeyClickSound()
        if (!ch.isLetter() && ch != '\'' && ch != '-') {
            // Non-letter punctuation: commit whatever composing text we have as plain Vietnamese
            // (so we stay fully compatible with Quốc Ngữ typing) and then insert the raw symbol.
            commitComposing()
            currentInputConnection?.commitText(ch.toString(), 1)
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
        val toneStyleOld = prefs.getString("pref_tone_style", "old") != "new"
        val newTail = TelexEngine.apply(tail, ch, toneStyleOld)
        composing = head + newTail
        updateComposing()
    }

    override fun onBackspace() {
        playKeyClickSound()
        if (composing.isNotEmpty()) {
            composing = composing.dropLast(1)
            updateComposing()
        } else {
            // Delegate to the text field: send a DEL key event
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onEnter() {
        playKeyClickSound()
        commitComposing()
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
        // Space behaviour (designed to be fully Quốc-Ngữ friendly, i.e. typing pure Vietnamese
        // sentences keeps working transparently):
        //
        //   - If the composing buffer is empty: just insert a literal space.
        //   - If the composing buffer ends with the last syllable still being typed (e.g. "anh"):
        //     DO NOT pick a candidate and DO NOT leave the composing zone. Instead, append a
        //     space to the composing buffer ("anh "). The text field still shows the whole
        //     buffer with the Android composing underline, and the candidate bar now looks up
        //     the multi-syllable compound (e.g. "anh quoc" -> 英國 once the user types more).
        //   - If the composing buffer already ends with a space ("anh ") and the user presses
        //     space AGAIN without typing anything in between: the user clearly wants a real
        //     space in the text – commit the composing text (minus the trailing space we were
        //     holding) as plain Vietnamese, then emit the literal space.
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
        // If the current composing buffer has NO Nom candidates whatsoever, there is nothing
        // to gain by staying in composing mode – the user has typed a word the dictionary
        // doesn't know (could be a name, a foreign word, or simply not in our corpus). In
        // that case we commit the plain Vietnamese text immediately and emit a real space,
        // so the text field doesn't show a lingering underline / composing highlight.
        //
        // We peek the dictionary directly (bypassing the prefix-completion branch that
        // [NomDictionary.lookup] also consults) because prefix completions are interesting
        // while the user is still typing but NOT a reason to keep the composing zone alive
        // after they pressed space.
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
        // Otherwise: extend the composing buffer with a syllable separator.
        composing += " "
        updateComposing()
    }
    override fun onSymbol(text: String) {
        commitComposing()
        currentInputConnection?.commitText(text, 1)
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
            // Use the trimmed form so we don't leak the internal syllable-separator space
            // (see commitComposing for the same rationale).
            val raw = text.trimEnd()
            if (raw.isNotEmpty()) {
                currentInputConnection?.commitText(raw, 1)
            }
            composing = ""
            currentCandidates = emptyList()
            updateComposing()
            return
        }
        currentInputConnection?.commitText(text, 1)
        bumpRecent(text)
        composing = ""
        currentCandidates = emptyList()
        updateComposing()
    }

    // ============================ Composing & candidate refresh ============================

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        val showCandidate = prefs.getBoolean("pref_show_candidates", true)
        if (composing.isEmpty()) {
            ic.setComposingText("", 1)
            ic.finishComposingText()
            candidateBar.clear()
            currentCandidates = emptyList()
            if (::candidateBar.isInitialized) candidateBar.visibility = View.GONE
            return
        }
        ic.setComposingText(composing, 1)
        candidateBar.setComposing(composing)
        val list = NomDictionary.lookup(composing.trim())
        // Sort by recent-usage frequency, stable ordering preserves dictionary order otherwise
        val sorted = list.sortedByDescending { recentCounts.getOrDefault(it, 0) }
        candidateBar.setCandidates(sorted)
        currentCandidates = sorted
        candidateBar.visibility = if (showCandidate) View.VISIBLE else View.GONE
    }

    private fun commitComposing() {
        if (composing.isEmpty()) return
        // Trim the trailing syllable-separator space we may have appended when the user hit
        // SPACE between syllables but then jumped out of the composing zone via Enter /
        // punctuation / language switch etc. We don't want to leak that internal separator
        // into the final committed text.
        val text = composing.trimEnd()
        if (text.isNotEmpty()) {
            currentInputConnection?.commitText(text, 1)
        }
        composing = ""
        candidateBar.clear()
        currentInputConnection?.finishComposingText()
        maybeAutoShift()
    }

    private fun resetComposing(commit: Boolean) {
        if (commit) commitComposing()
        composing = ""
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
        if (!prefs.getBoolean("pref_auto_caps", true)) return
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
