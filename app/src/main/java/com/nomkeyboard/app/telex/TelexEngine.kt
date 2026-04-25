package com.nomkeyboard.app.telex

/**
 * Telex Vietnamese input engine.
 * -----------------------------------------------
 * Telex rules (the most common Vietnamese input method):
 *   Vowel modifiers: aa -> â   aw -> ă   ee -> ê   oo -> ô   ow / [ -> ơ   uw / ] -> ư   dd -> đ
 *   Tone marks:      s -> sắc   (acute)
 *                    f -> huyền (grave)
 *                    r -> hỏi   (hook above)
 *                    x -> ngã   (tilde)
 *                    j -> nặng  (dot below)
 *                    z -> clear tone
 *
 * Example: "tieengs" -> "tiếng"  (double "ee" becomes "ê", then "s" adds the acute tone)
 *
 * Strategy:
 *   - The IME keeps a "composing" buffer (letters typed so far for the current word).
 *   - Each incoming character is fed to [apply], which attempts a minimal rewrite of the buffer
 *     (e.g. replace the last vowel, or merge the last two letters). If nothing matches, the
 *     character is simply appended.
 *
 * This mirrors the behaviour of Gboard's Vietnamese Telex layout.
 */
object TelexEngine {

    // Two-character diphthong rewrites produced by typing an 'o' AFTER a horn vowel:
    //   ư + o -> ươ,  Ư + o -> Ươ,  ư + O -> ưO? (we prefer matching case of the trigger)
    // This is needed because a user who already typed "ư" (e.g. via the standalone-'w'
    // shortcut) and then types 'o' expects the standard Vietnamese diphthong "ươ", not the
    // literal two characters "ưo". The same applies to the uppercase variants.
    private val hornFollowUp: Map<Pair<Char, Char>, String> = mapOf(
        'ư' to 'o' to "ươ",
        'Ư' to 'o' to "Ươ",
        'ư' to 'O' to "ưƠ",
        'Ư' to 'O' to "ƯƠ",
    )

    // Double-letter vowel modifiers: (existingChar, triggerChar) -> mergedChar.
    // NOTE: "uw" / "ow" work even when the 'u' / 'o' is not the last character of the buffer,
    // because real Vietnamese Telex rewrites the most recent matching vowel – that logic lives
    // in [apply] below. This table only records the pairwise rewrite result.
    private val vowelMod: Map<Pair<Char, Char>, Char> = mapOf(
        'a' to 'a' to 'â',
        'a' to 'w' to 'ă',
        'e' to 'e' to 'ê',
        'o' to 'o' to 'ô',
        'o' to 'w' to 'ơ',
        'u' to 'w' to 'ư',
        'd' to 'd' to 'đ',
        'A' to 'A' to 'Â',
        'A' to 'W' to 'Ă',
        'E' to 'E' to 'Ê',
        'O' to 'O' to 'Ô',
        'O' to 'W' to 'Ơ',
        'U' to 'W' to 'Ư',
        'D' to 'D' to 'Đ',
        // mixed-case variants: both aw and aW should produce ă
        'a' to 'W' to 'ă',
        'A' to 'w' to 'Ă',
        'e' to 'E' to 'ê',
        'E' to 'e' to 'Ê',
        'o' to 'W' to 'ơ',
        'O' to 'w' to 'Ơ',
        'u' to 'W' to 'ư',
        'U' to 'w' to 'Ư',
        'd' to 'D' to 'đ',
        'D' to 'd' to 'Đ',
        'a' to 'A' to 'â',
        'A' to 'a' to 'Â',
        'o' to 'O' to 'ô',
        'O' to 'o' to 'Ô',
    )

    // Vowel set (including modified vowels with hat/breve/horn)
    private val vowels = "aAăĂâÂeEêÊiIoOôÔơƠuUưƯyY"

    // Tones: 0=none, 1=sắc (acute), 2=huyền (grave), 3=hỏi (hook above), 4=ngã (tilde), 5=nặng (dot below)
    // Map from base vowel -> 6-element array (untoned + 5 tones)
    private val toneMap: Map<Char, CharArray> = buildMap {
        put('a', charArrayOf('a', 'á', 'à', 'ả', 'ã', 'ạ'))
        put('ă', charArrayOf('ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'))
        put('â', charArrayOf('â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'))
        put('e', charArrayOf('e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'))
        put('ê', charArrayOf('ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'))
        put('i', charArrayOf('i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'))
        put('o', charArrayOf('o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'))
        put('ô', charArrayOf('ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'))
        put('ơ', charArrayOf('ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'))
        put('u', charArrayOf('u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'))
        put('ư', charArrayOf('ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'))
        put('y', charArrayOf('y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'))
        put('A', charArrayOf('A', 'Á', 'À', 'Ả', 'Ã', 'Ạ'))
        put('Ă', charArrayOf('Ă', 'Ắ', 'Ằ', 'Ẳ', 'Ẵ', 'Ặ'))
        put('Â', charArrayOf('Â', 'Ấ', 'Ầ', 'Ẩ', 'Ẫ', 'Ậ'))
        put('E', charArrayOf('E', 'É', 'È', 'Ẻ', 'Ẽ', 'Ẹ'))
        put('Ê', charArrayOf('Ê', 'Ế', 'Ề', 'Ể', 'Ễ', 'Ệ'))
        put('I', charArrayOf('I', 'Í', 'Ì', 'Ỉ', 'Ĩ', 'Ị'))
        put('O', charArrayOf('O', 'Ó', 'Ò', 'Ỏ', 'Õ', 'Ọ'))
        put('Ô', charArrayOf('Ô', 'Ố', 'Ồ', 'Ổ', 'Ỗ', 'Ộ'))
        put('Ơ', charArrayOf('Ơ', 'Ớ', 'Ờ', 'Ở', 'Ỡ', 'Ợ'))
        put('U', charArrayOf('U', 'Ú', 'Ù', 'Ủ', 'Ũ', 'Ụ'))
        put('Ư', charArrayOf('Ư', 'Ứ', 'Ừ', 'Ử', 'Ữ', 'Ự'))
        put('Y', charArrayOf('Y', 'Ý', 'Ỳ', 'Ỷ', 'Ỹ', 'Ỵ'))
    }

    // Reverse map: toned vowel -> (base vowel, tone index)
    private val toneReverse: Map<Char, Pair<Char, Int>> = buildMap {
        for ((base, arr) in toneMap) {
            for ((i, c) in arr.withIndex()) {
                put(c, base to i)
            }
        }
    }

    private val toneTriggers = mapOf('s' to 1, 'f' to 2, 'r' to 3, 'x' to 4, 'j' to 5, 'z' to 0)

    // "Restore-original" (a.k.a. undo) table: if the user already produced a modified vowel
    // and then types the SAME trigger key again, standard Unikey / Gboard behaviour is to
    // un-merge – e.g. typing 'a' after "â" yields "aa" (meaning the user really wanted two
    // literal a's, not a circumflex). The mapping key is (existingModifiedVowel, retriggerChar)
    // and the value is the two literal characters to restore.
    private val undoMergeMap: Map<Pair<Char, Char>, String> = mapOf(
        'â' to 'a' to "aa",
        'ă' to 'w' to "aw",
        'ê' to 'e' to "ee",
        'ô' to 'o' to "oo",
        'ơ' to 'w' to "ow",
        'ư' to 'w' to "uw",
        'đ' to 'd' to "dd",
        'Â' to 'A' to "AA",
        'Ă' to 'W' to "AW",
        'Ê' to 'E' to "EE",
        'Ô' to 'O' to "OO",
        'Ơ' to 'W' to "OW",
        'Ư' to 'W' to "UW",
        'Đ' to 'D' to "DD",
        // Mixed-case retriggers (user typed the initial capital but retriggers with lowercase)
        'Â' to 'a' to "Aa",
        'Ă' to 'w' to "Aw",
        'Ê' to 'e' to "Ee",
        'Ô' to 'o' to "Oo",
        'Ơ' to 'w' to "Ow",
        'Ư' to 'w' to "Uw",
        'Đ' to 'd' to "Dd",
    )

    /**
     * Core entry point: given the current composing buffer and a new character,
     * return the new composing buffer after applying Telex rules.
     * If [ch] does not trigger any transformation, the result is simply [composing] + [ch].
     *
     * [toneStyleOld] controls where the tone mark lands on OPEN syllables whose nucleus is a
     * falling diphthong such as `oa / oe / uy`:
     *   - true  (traditional / old style): place the tone on the FIRST vowel of the diphthong
     *     -> `hóa`, `hòe`, `thúy`, `quý`.
     *   - false (modern / new style): place it on the LAST vowel -> `hoá`, `hoè`, `thuý`, `quý`.
     * Closed syllables (with a trailing consonant) and syllables carrying a modified vowel
     * (ơ/ê/â/ô/ă) are unaffected – those rules are unambiguous across styles.
     */
    fun apply(composing: String, ch: Char, toneStyleOld: Boolean = true): String {
        // "w" typed on its own (no prior letters, at word start, or after a non-letter) expands
        // to "ư" – a very common shortcut most Vietnamese Telex engines provide.
        if ((ch == 'w' || ch == 'W') && (composing.isEmpty() || !composing.last().isLetter())) {
            val merged = if (ch == 'W') 'Ư' else 'ư'
            return composing + merged
        }

        if (composing.isEmpty()) return ch.toString()

        // 0. Restore-original (undo) shortcut: if the trailing character is a modified vowel
        //    whose producer was exactly this trigger key, un-merge back to the two literal
        //    characters. This is the standard Unikey / Gboard escape hatch so that real
        //    foreign words like "laptop", "email", "zoom" can still be typed – the user types
        //    the trigger letter a SECOND time to cancel the merge.
        //
        //    Examples: "â" + 'a' -> "aa"   (user wanted a literal double a)
        //              "ư" + 'w' -> "uw"   (user really wanted the letter w)
        //              "đ" + 'd' -> "dd"
        undoMergeMap[composing.last() to ch]?.let { restored ->
            return composing.dropLast(1) + restored
        }

        // 0b. Tone-undo: same idea for tone marks. If the trigger is s/f/r/x/j and the syllable
        //     already carries THAT specific tone, pressing the trigger again strips the tone
        //     AND appends the literal trigger character (so "as" -> "á", "ás" -> "as").
        //     This is vital for words that genuinely end in the trigger letter ("nice", "cafj",
        //     "bus" etc. in mixed-language input).
        val loweredCh = ch.lowercaseChar()
        if (loweredCh in "sfrxj") {
            val triggerTone = toneTriggers[loweredCh]!!
            val tonedIdx = findExistingToneIndex(composing, triggerTone)
            if (tonedIdx >= 0) {
                val tonedCh = composing[tonedIdx]
                val base = toneReverse[tonedCh]?.first ?: tonedCh
                val arr = toneMap[base] ?: return composing + ch
                val sb = StringBuilder(composing)
                sb.setCharAt(tonedIdx, arr[0]) // strip tone, keep base
                sb.append(ch)
                return sb.toString()
            }
        }

        // 1a. The 'w' key is overloaded – horn/breve/undo/literal – all the rules live in
        //     [rewriteHornForW]. In brief:
        //        * syllable contains a plain u/o/a -> add horn/breve to the nearest one;
        //        * syllable already contains a w-produced modified vowel (ă/ơ/ư, untoned)
        //          -> undo that merge and append a literal 'w' (e.g. "băn" + w -> "banw");
        //        * syllable contains a non-w modified vowel (â/ê/ô) or a toned vowel
        //          -> just append a literal 'w';
        //        * nothing letter-like in the syllable -> append a standalone "ư"
        //          (covers "ng" + w -> "ngư").
        if (ch == 'w' || ch == 'W') {
            val rewritten = rewriteHornForW(composing, triggerUpper = ch == 'W')
            if (rewritten != null) return rewritten
            return composing + if (ch == 'W') 'Ư' else 'ư'
        }

        // 1b. Horn follow-up: if the user already has a horn vowel (ư/Ư) at the tail and then
        //     types 'o'/'O', form the diphthong "ươ" / "Ươ" / "ưƠ" / "ƯƠ" automatically.
        val last = composing.last()
        hornFollowUp[last to ch]?.let { merged ->
            return composing.dropLast(1) + merged
        }

        // 1c. Vowel modifier (double-letter merge): last char + ch forms a modifier pair
        val pairKey = last to ch
        vowelMod[pairKey]?.let { merged ->
            // replace the last character with the merged one
            return composing.dropLast(1) + merged
        }

        // 1d. "Long-distance" double-vowel merge: for the same-vowel pairs (aa/ee/oo), we also
        //     want to merge even if the two vowels are separated by consonants typed in
        //     between. Real Vietnamese Telex lets you type "quoc" then 'o' and get "quôc" –
        //     the 'o' trigger retroactively gives the existing 'o' its circumflex (hat). This
        //     mirrors what Gboard / Unikey / Laban do.
        //
        //     Only applies to the "double the same vowel" family (a/e/o/A/E/O) where a hat
        //     result exists; 'w' already has its own dedicated long-distance logic above.
        longDistanceHatFor(ch)?.let { (targetBase, hatChar) ->
            val rewritten = rewriteHatLongDistance(composing, targetBase, hatChar)
            if (rewritten != null) return rewritten
        }

        // 2. Tone marks (lowercase trigger keys s/f/r/x/j/z)
        val lowerCh = ch.lowercaseChar()
        val tone = toneTriggers[lowerCh]
        // Only trigger if the buffer already contains a vowel to attach the tone to AND the
        // last syllable looks like a valid Vietnamese one. The latter check is important
        // because real Telex engines (Unikey / Gboard) refuse to place a tone on a syllable
        // whose structure is obviously invalid – e.g. two disjoint vowel clusters separated
        // by a consonant like "nếuma" – and instead emit the trigger letter literally so the
        // user can keep typing foreign words / run-on syllables transparently.
        if (tone != null && composing.any { it.isVowelLike() } && isLastSyllablePhonotacticallyValid(composing)) {
            val idx = findToneTargetIndex(composing, toneStyleOld)
            if (idx >= 0) {
                val v = composing[idx]
                val base = toneReverse[v]?.first ?: v
                val arr = toneMap[base]
                if (arr != null) {
                    val newCh = arr[tone]
                    val sb = StringBuilder(composing)
                    sb.setCharAt(idx, newCh)
                    return sb.toString()
                }
            }
        }

        // 2b. Tone relocation. The incoming char is not a tone trigger and didn't match any
        //     merge rule above, so it will be appended literally. But if the syllable
        //     ALREADY carries a tone and appending [ch] changes which vowel position is the
        //     "correct" carrier (per current orthography rules), we must move the tone to
        //     the new carrier. Two classic scenarios this fixes:
        //
        //       (new style) "thủ" + 'y' -> "thuỷ"   (open diphthong uy: tone moves u -> y)
        //       (old style) "búy" + 't' -> "buýt"   (syllable closes:  tone moves u -> y)
        //
        //     Generic recipe:
        //       - find the currently toned vowel (if any);
        //       - build the hypothetical buffer = composing + ch with the tone stripped
        //         from its old position;
        //       - ask findToneTargetIndex where the tone SHOULD live on that buffer;
        //       - if it's a different index, relocate.
        if (ch.isLetter()) {
            val relocated = relocateTone(composing, ch, toneStyleOld)
            if (relocated != null) return relocated
        }

        // 3. Default: append the character as-is
        return composing + ch
    }

    /**
     * Attempts to move an existing tone mark to a new vowel when appending [ch] changes the
     * canonical tone-bearing position.
     *
     * Returns the new buffer if relocation happened, or null to let the caller fall through
     * to the default "append as-is" behaviour.
     *
     * Scope: only considers the last syllable (anything after the most recent non-letter).
     * Silently bails out when the (composing + ch) last syllable is not a well-formed
     * Vietnamese shape so foreign words stay intact.
     */
    private fun relocateTone(composing: String, ch: Char, toneStyleOld: Boolean): String? {
        // Locate the currently-toned vowel inside the last syllable only.
        var syllableStart = composing.length
        for (i in composing.indices.reversed()) {
            if (!composing[i].isLetter()) break
            syllableStart = i
        }
        if (syllableStart >= composing.length) return null
        var oldToneIdx = -1
        var oldToneNum = 0
        for (i in syllableStart until composing.length) {
            val entry = toneReverse[composing[i]] ?: continue
            if (entry.second != 0) {
                oldToneIdx = i
                oldToneNum = entry.second
                break
            }
        }
        if (oldToneIdx < 0) return null

        // Hypothetical buffer: strip the tone from its current position and append ch.
        val oldTonedCh = composing[oldToneIdx]
        val oldBase = toneReverse[oldTonedCh]!!.first
        val stripped = StringBuilder(composing).apply { setCharAt(oldToneIdx, oldBase) }.append(ch).toString()

        // Only reconsider tone placement when the resulting last syllable is still a legal
        // Vietnamese shape – otherwise the user is likely typing a non-Vietnamese word and
        // we shouldn't move diacritics around.
        if (!isLastSyllablePhonotacticallyValid(stripped)) return null

        val newIdx = findToneTargetIndex(stripped, toneStyleOld)
        if (newIdx < 0 || newIdx == oldToneIdx) return null

        // Relocate: put the same tone onto the vowel at newIdx, preserving any existing
        // hat/horn/breve modification on that vowel.
        val targetCh = stripped[newIdx]
        val targetBase = toneReverse[targetCh]?.first ?: targetCh
        val arr = toneMap[targetBase] ?: return null
        val sb = StringBuilder(stripped)
        sb.setCharAt(newIdx, arr[oldToneNum])
        return sb.toString()
    }

    /**
     * Cheap phonotactic sanity-check on the last syllable (portion of [composing] after the
     * most recent non-letter character). A well-formed Vietnamese syllable has at most ONE
     * contiguous vowel cluster – structure is (C1) V+ (C2), where V+ can be up to three
     * vowels (as in "khuya", "ngoài") but never interrupted by a consonant.
     *
     * When the last syllable contains two or more separate vowel runs (e.g. "nếuma" with the
     * runs "ếu" and "a" split by 'm'), the user is clearly typing past a syllable boundary
     * that they forgot to delimit with a space – or typing a non-Vietnamese word. In both
     * cases a tone trigger should NOT retroactively decorate the earlier vowel; instead the
     * trigger letter must pass through literally so the user can type words like "nếumaf"
     * or "banmaf" without the IME interfering.
     *
     * Also returns false when the last syllable is empty (no letters), because there's
     * nothing sensible to attach a tone to.
     */
    private fun isLastSyllablePhonotacticallyValid(composing: String): Boolean {
        // Find the start of the last syllable (skip back through letters).
        var start = composing.length
        for (i in composing.indices.reversed()) {
            if (!composing[i].isLetter()) break
            start = i
        }
        if (start >= composing.length) return false
        var vowelRuns = 0
        var inVowel = false
        // Track whether we've already seen a toned vowel and count how many further vowels
        // appear after it. A Vietnamese syllable carries at most one tone, and the only
        // canonical shapes where a toned vowel is followed by more letters have AT MOST
        // one trailing vowel in the same vowel cluster (e.g. "nếu" with 'u' after toned
        // 'ế', or "ngoài" with 'i' after toned 'à'). If we see two or more vowels after
        // the tone carrier, the syllable has extended beyond any legal Vietnamese vowel
        // cluster (e.g. "nếua" with the extra 'ua' past 'ế') – refuse to apply another
        // tone trigger in that case so the trigger letter passes through literally.
        var sawTonedVowel = false
        var vowelsAfterTone = 0
        for (i in start until composing.length) {
            val ch = composing[i]
            val v = ch.isVowelLike()
            if (v && !inVowel) {
                vowelRuns++
                if (vowelRuns >= 2) return false
            }
            if (v && sawTonedVowel) vowelsAfterTone++
            val entry = toneReverse[ch]
            if (entry != null && entry.second != 0) sawTonedVowel = true
            inVowel = v
        }
        if (sawTonedVowel && vowelsAfterTone >= 2) return false
        // Exactly one vowel run means a canonical Vietnamese syllable shape; zero vowels
        // (all consonants) means there's no place for a tone so we still return false.
        return vowelRuns == 1
    }

    /**
     * Return the index of a vowel in [s] that already carries exactly the [toneIdx] tone
     * (1=sắc, 2=huyền, 3=hỏi, 4=ngã, 5=nặng). Used by the tone-undo logic. Returns -1 if none.
     */
    private fun findExistingToneIndex(s: String, toneIdx: Int): Int {
        for ((i, ch) in s.withIndex()) {
            val entry = toneReverse[ch] ?: continue
            if (entry.second == toneIdx) return i
        }
        return -1
    }

    private fun Char.isVowelLike(): Boolean = toneReverse.containsKey(this) || vowels.contains(this)

    /**
     * Apply the appropriate "w"-trigger rewrite. The Telex 'w' key is overloaded:
     *   - Standalone (no prior letters) -> append ư.
     *   - After (or within a syllable containing) u/o, it adds the horn: u -> ư, o -> ơ.
     *     When the target is 'o' and the immediately preceding char is 'u', both get the horn
     *     (the "uo" diphthong becomes "ươ").
     *   - After (or within a syllable containing) 'a'/'A', it adds the breve: a -> ă.
     *   - If the syllable ALREADY contains a w-produced modified vowel (ă/ơ/ư plus uppercase
     *     variants, without any tone), the new 'w' press undoes that merge – so "băn"+w
     *     becomes "banw" (user presses the trigger twice to get back the literal two-letter
     *     form). This matches Unikey's "restore original" behaviour extended to the whole
     *     syllable.
     *   - If the syllable contains an unrelated modified vowel (â/ê/ô) or a toned vowel, we
     *     stop scanning and append a literal 'w' – the user has clearly moved on from the
     *     horn/breve decision.
     *   - If no letter is present in the current syllable at all, fall back to appending a
     *     standalone ư.
     *
     * Scanning is bounded by the current syllable: we stop at the first non-letter char.
     * The case of the produced vowel follows the case of the scanned source vowel, not the
     * case of the 'w' trigger itself.
     *
     * Returns null when the caller should fall back to the "append standalone ư" branch.
     */
    private fun rewriteHornForW(composing: String, triggerUpper: Boolean): String? {
        val literalW: Char = if (triggerUpper) 'W' else 'w'

        // Case 2: bulk undo. If the current syllable already contains ANY w-produced modified
        // vowel (ă/ơ/ư, no tone), revert ALL of them in a single keystroke and append a
        // literal 'w'. This way pressing 'w' a second time gives back the exact letter
        // sequence the user originally typed:
        //     "băn"   + w -> "banw"
        //     "ngươi" + w -> "nguoiw"   (both ư and ơ revert together)
        //     "tư"    + w -> "tuw"
        // We find the syllable boundary first (stopping at any non-letter), then do a single
        // pass replacement over that slice.
        val syllableStart = run {
            var idx = composing.length
            for (i in composing.indices.reversed()) {
                if (!composing[i].isLetter()) break
                idx = i
            }
            idx
        }
        val syllable = composing.substring(syllableStart)
        if (syllable.any { it in "ăĂơƠưƯ" }) {
            val sb = StringBuilder(syllable.length)
            for (c in syllable) {
                sb.append(
                    when (c) {
                        'ă' -> 'a'; 'Ă' -> 'A'
                        'ơ' -> 'o'; 'Ơ' -> 'O'
                        'ư' -> 'u'; 'Ư' -> 'U'
                        else -> c
                    }
                )
            }
            return composing.substring(0, syllableStart) + sb.toString() + literalW
        }

        // Case 3: cluster-based rewrite. Locate the vowel cluster inside the current
        // syllable (optionally past a gi-/qu- prefix glide), classify its shape, and apply
        // the horn/breve according to Vietnamese orthography. Tones that already sit on a
        // cluster vowel are preserved in place.
        //
        // Accepted clusters (case-insensitive, after skipping a gi-/qu- prefix) and the
        // resulting w-target:
        //     a          -> a gets breve     (ban -> băn, a -> ă, gia -> giă, cha -> chă)
        //     o          -> o gets horn      (co -> cơ, o -> ơ, gio -> giơ, quo -> quơ)
        //     u          -> u gets horn      (tu -> tư, u -> ư, giu -> giư, thu -> thư)
        //     oi         -> o gets horn      (coi -> cơi)
        //     ui         -> u gets horn      (tui -> tưi)
        //     ua         -> u gets horn      (ua -> ưa, cua -> cưa, mua -> mưa)
        //     uu         -> first u gets horn (uu -> ưu, cuu -> cưu)
        //     oa         -> a gets breve     (oa -> oă, hoa -> hoă, gioa -> gioă, qua -> quă)
        //     uo(+coda)  -> double (u+o both, "ươ")  (buon -> bươn, tuong -> tương, cuoi -> cươi)
        //
        // Exception: under the qu- prefix, the "u" belongs to the onset glide and does NOT
        // participate in a uo double-promotion. Since gi-/qu- glides are stripped before
        // cluster detection, the remaining cluster is only the post-glide vowels, so the
        // "quo" case naturally classifies as "o" (single horn) -> "quơ" and "qua" as "a"
        // (breve) -> "quă". Likewise "gio"/"giu" collapse to "o"/"u" after skipping the
        // gi- glide.
        //
        // Anything NOT in the table above -> literal 'w' (e.g. ao, au, ai, ay, oe, ue,
        // ia, uy, uya, khuya, bau, bao, cao, bai, tay, tui-y? no, tuy, quy, que, qui,
        // giao, giau, khuoa, mia, thue, hoe).
        val rewritten = applyClusterW(composing, syllableStart)
        if (rewritten != null) return rewritten

        // No cluster match – append a literal 'w'. If the syllable had no letters at all,
        // fall through to the caller which emits a standalone "ư".
        if (composing.substring(syllableStart).any { it.isLetter() }) {
            return composing + literalW
        }
        return null
    }

    /**
     * Cluster-based w-rewrite. Inspects the syllable starting at [syllableStart] in
     * [composing], skips a leading gi-/qu- glide when applicable, locates the first vowel
     * cluster, classifies its shape by stripping tones/modifiers to base letters, and
     * applies the horn/breve per Vietnamese orthography. Returns the rewritten buffer or
     * null if no cluster rule matches (meaning the caller should emit a literal 'w').
     */
    private fun applyClusterW(composing: String, syllableStart: Int): String? {
        // Skip a gi-/qu- onset glide (only when there is a vowel after it, matching
        // glideSkipOffset semantics but scoped to the current syllable).
        val syllableSub = composing.substring(syllableStart)
        val postGlideOffset = run {
            if (syllableSub.length < 3) return@run 0
            val c0 = syllableSub[0].lowercaseChar()
            val c1 = syllableSub[1].lowercaseChar()
            val hasFurtherVowel = (2 until syllableSub.length).any { syllableSub[it].isVowelLike() }
            if (!hasFurtherVowel) return@run 0
            if ((c0 == 'g' && c1 == 'i') || (c0 == 'q' && c1 == 'u')) 2 else 0
        }
        val clusterSearchStart = syllableStart + postGlideOffset

        // Locate first vowel run in [clusterSearchStart, end).
        var clusterStart = -1
        var clusterEnd = -1 // exclusive
        var i = clusterSearchStart
        while (i < composing.length) {
            if (composing[i].isVowelLike()) { clusterStart = i; break }
            i++
        }
        if (clusterStart < 0) return null
        i = clusterStart
        while (i < composing.length && composing[i].isVowelLike()) i++
        clusterEnd = i

        // The cluster must be the ONLY vowel run in the remainder of the syllable – any
        // later vowel after a consonant means the buffer isn't a canonical Vietnamese
        // syllable and we shouldn't try to rewrite.
        while (i < composing.length) {
            if (composing[i].isVowelLike()) return null
            i++
        }

        // Build the lowercase base-letter form of the cluster (strip tone and horn/breve/
        // circumflex). If ANY cluster vowel isn't in {a, o, u, e, i, y} at its base, we
        // can still handle it as long as it matches one of the base-cluster keys below;
        // otherwise fall through.
        val baseChars = StringBuilder(clusterEnd - clusterStart)
        for (k in clusterStart until clusterEnd) {
            val ch = composing[k]
            val base = (toneReverse[ch]?.first ?: ch).lowercaseChar()
            val plain = when (base) {
                'ă', 'â' -> 'a'
                'ê' -> 'e'
                'ô', 'ơ' -> 'o'
                'ư' -> 'u'
                else -> base
            }
            baseChars.append(plain)
        }
        val clusterKey = baseChars.toString()

        // Decide the per-position action: for each cluster index (0-based), either leave
        // untouched, add horn (o->ơ, u->ư), or add breve (a->ă). Null action means this
        // cluster isn't eligible for w-rewrite (caller emits literal 'w').
        // Action codes: 0 = keep, 1 = horn, 2 = breve.
        val actions: IntArray? = when (clusterKey) {
            "a" -> intArrayOf(2)
            "o" -> intArrayOf(1)
            "u" -> intArrayOf(1)
            "oi" -> intArrayOf(1, 0)
            "ui" -> intArrayOf(1, 0)
            "ua" -> intArrayOf(1, 0)
            "uu" -> intArrayOf(1, 0)
            "oa" -> intArrayOf(0, 2)
            "uo" -> intArrayOf(1, 1)
            "uoi" -> intArrayOf(1, 1, 0)
            else -> null
        }
        if (actions == null) return null

        // If any cluster vowel already carries the TARGET w-modification (ă/ơ/ư), we
        // shouldn't get here – Case 2 (bulk undo) would have fired first. But if a vowel
        // already has a non-w modification (â/ê/ô, possibly toned like ố/ế/ấ) the w can't
        // land on it: emit literal.
        for (k in clusterStart until clusterEnd) {
            val ch = composing[k]
            val base = toneReverse[ch]?.first ?: ch
            if (base in "âÂêÊôÔ") return null
        }

        val sb = StringBuilder(composing)
        for ((idx, act) in actions.withIndex()) {
            if (act == 0) continue
            val pos = clusterStart + idx
            val ch = composing[pos]
            val entry = toneReverse[ch]
            val tone = entry?.second ?: 0
            val baseCh = entry?.first ?: ch
            val newBase: Char = when (baseCh) {
                'a' -> 'ă'; 'A' -> 'Ă'
                'o' -> if (act == 1) 'ơ' else return null
                'O' -> if (act == 1) 'Ơ' else return null
                'u' -> if (act == 1) 'ư' else return null
                'U' -> if (act == 1) 'Ư' else return null
                else -> return null
            }
            val repl = toneMap[newBase]?.get(tone) ?: return null
            sb.setCharAt(pos, repl)
        }
        return sb.toString()
    }

    /**
     * If [ch] is a trigger that can produce a hat (â / ê / ô / uppercase variants) via the
     * long-distance "double same-vowel" rule, return the (target-base-vowel, hat-result) pair.
     * Otherwise return null. See [rewriteHatLongDistance] for the actual scan-and-replace logic.
     */
    private fun longDistanceHatFor(ch: Char): Pair<Char, Char>? = when (ch) {
        'a' -> 'a' to 'â'
        'e' -> 'e' to 'ê'
        'o' -> 'o' to 'ô'
        'A' -> 'A' to 'Â'
        'E' -> 'E' to 'Ê'
        'O' -> 'O' to 'Ô'
        else -> null
    }

    /**
     * Scan [composing] backwards inside the current syllable (i.e. until a non-letter or a
     * space) looking for the most recent [targetBase] vowel that has not yet received any
     * diacritic. If found, replace it with [hatChar] and return the new buffer; otherwise
     * return null so the caller can fall through to the default "append as-is" branch.
     *
     * Rationale: a user typing "quoc" then 'o' expects "quôc" – the engine must reach past the
     * intervening 'c' to find the bare 'o' waiting to be upgraded to 'ô'. We intentionally
     * restrict this to the same-case bare vowel to avoid overeager rewrites (e.g. we don't
     * want typing 'o' after "cô" to do anything strange).
     */
    private fun rewriteHatLongDistance(composing: String, targetBase: Char, hatChar: Char): String? {
        for (i in composing.indices.reversed()) {
            val c = composing[i]
            if (!c.isLetter()) break
            if (c == targetBase) {
                return composing.substring(0, i) + hatChar + composing.substring(i + 1)
            }
            // If we hit a vowel that already has a diacritic from the same base family, stop
            // scanning – the user has already tone-decorated this syllable's vowel and is now
            // typing a plain letter, which should just be appended.
            if (c == hatChar) return null
        }
        return null
    }

    /**
     * Pick the best vowel position in [s] to carry the tone mark, applying the standard
     * Vietnamese orthography rules that Unikey / Gboard use:
     *
     *   1. Special prefix handling:
     *        - "gi" where the i IS the only vowel (e.g. "gi", "gif" -> "gì"): the tone
     *          must go on that i.
     *        - "qu" where the u is followed by another vowel (e.g. "quy", "quyen"): the u is
     *          a glide that does NOT carry the tone; skip past it.
     *   2. Prefer the modified vowels (ơ / ê / â / ô / ă) when present – they always win.
     *   3. Otherwise, if the word ends with a consonant, put the tone on the vowel
     *      immediately before the trailing consonant cluster (the "nucleus" rule, e.g.
     *      "toan" -> "toán", "hoang" -> "hoàng").
     *   4. Otherwise (open syllable, i.e. last char is a vowel) the choice depends on the
     *      diphthong shape:
     *        - FALLING diphthongs ending in a glide (ai, ay, ao, au, eo, êu, iu, ơi, ưi, ...):
     *          the tone ALWAYS goes on the first vowel (the nucleus) -> "gái", "sáo", "éo".
     *          No one writes "gaí" in any tradition.
     *        - RISING diphthongs starting with an o/u glide (oa, oe, uy, uê, ...):
     *          * old (traditional): first vowel  -> "hóa", "hòe", "thúy".
     *          * new (modern):      second vowel -> "hoá", "hoè", "thuý".
     *   5. Fallback: the last vowel in the buffer.
     *
     * [toneStyleOld] only influences rising-diphthong open syllables; everything else is
     * written identically in both orthographic traditions.
     */
    private fun findToneTargetIndex(s: String, toneStyleOld: Boolean = true): Int {
        // Compute effective "vowel scan start": skip a leading gi-/qu- glide when appropriate.
        val scanStart = glideSkipOffset(s)

        // Rule 2: modified vowels take absolute priority.
        val priority = "ơƠêÊâÂôÔăĂ"
        for (i in s.indices.reversed()) {
            if (i < scanStart) break
            if (priority.contains(s[i])) return i
        }

        // Collect all vowel positions from scanStart onwards.
        val vowelIdx = ArrayList<Int>()
        for (i in scanStart until s.length) {
            if (s[i].isVowelLike()) vowelIdx.add(i)
        }
        if (vowelIdx.isEmpty()) {
            // If we skipped everything (e.g. input is just "qu" and no vowel after), fall
            // back to scanning the entire string so that "gi" + s -> "gì" still works via
            // the generic path below.
            for (i in s.indices) {
                if (s[i].isVowelLike()) vowelIdx.add(i)
            }
        }
        if (vowelIdx.isEmpty()) return -1
        if (vowelIdx.size == 1) return vowelIdx[0]
        val lastVowel = vowelIdx.last()
        val hasTrailingConsonant = lastVowel < s.length - 1
        if (hasTrailingConsonant) return lastVowel

        // Open-syllable rule. A Vietnamese open syllable with two vowels is either
        //   (a) a RISING diphthong where the first vowel is a glide (o/u) and the real
        //       nucleus is the second one – e.g. "hoa", "hoe", "uy", "uê", "quy"+extra.
        //       Examples: "hoas" -> hóa (old) / hoá (new).
        //   (b) a FALLING diphthong where the first vowel is the nucleus and the second
        //       vowel is the off-glide (i/y/o/u) – e.g. "gai", "sao", "eo", "ui", "ôi".
        //       Examples: "gais" -> gái (ALWAYS, both old and new); "saoj" -> sạo; "ôij" -> ộ... wait,
        //       ôi already has ô so the priority rule above handles it.
        //
        // In case (b) the tone ALWAYS sits on the first vowel (the nucleus), regardless of
        // the user's orthography preference – no one writes "gaí" / "saó" in either tradition.
        // In case (a) the old style keeps the tone on the first vowel for visual balance
        // ("hóa"), while the modern style follows pure phonology and places it on the
        // nucleus, i.e. the second vowel ("hoá").
        val firstVowel = vowelIdx[vowelIdx.size - 2]
        // A rising diphthong (on-glide + real nucleus) is one whose first vowel is an o/u
        // glide AND whose second vowel is NOT an i/y off-glide... wait, y IS the nucleus in
        // "uy" (thúy / thuý) – so the only letter that disqualifies is plain 'i'.
        // Concretely, the Vietnamese rising diphthongs/triphthongs are
        //     oa  oă  oe           (o-glide + nucleus a/ă/e)
        //     ua  uâ  uê  uơ  uy   (u-glide + nucleus a/â/ê/ơ/y)
        // Falling open-syllable diphthongs that also start with o/u are only the -i ones:
        //     oi  ôi  ơi  ui  ưi   (second vowel is i)
        // (oo/ou/uo/uu don't appear as standalone open syllables in modern Vietnamese, and
        // any "uo" input is rewritten to "uơ"/"uô" by the horn rule before we get here.)
        // So: it's rising iff firstCh ∈ {o,u} AND secondCh is NOT 'i'.
        val firstCh = s[firstVowel].lowercaseChar()
        val secondCh = s[lastVowel].lowercaseChar()
        val isRisingDiphthong = firstCh in "ou" && secondCh != 'i'
        return when {
            !isRisingDiphthong -> firstVowel   // falling diphthong: gái, sáo, éo, úi, ối...
            toneStyleOld -> firstVowel         // rising + old: hóa / hòe / thúy
            else -> lastVowel                  // rising + new: hoá / hoè / thuý
        }
    }

    /**
     * Return the index at which the effective "vowel scan" should start, taking into account
     * Vietnamese glide prefixes:
     *   - "gi" when followed by at least one more vowel (the 'i' is a glide): skip 2.
     *     If the 'i' is the ONLY vowel (e.g. input is exactly "gi" or "gin"), don't skip
     *     anything – the 'i' IS the nucleus.
     *   - "qu" when followed by at least one more vowel (the 'u' is a glide): skip 2.
     * Case-insensitive. Returns 0 when no glide applies.
     */
    private fun glideSkipOffset(s: String): Int {
        if (s.length < 3) return 0
        val c0 = s[0].lowercaseChar()
        val c1 = s[1].lowercaseChar()
        // Check whether there's any vowel at position >= 2.
        val hasFurtherVowel = (2 until s.length).any { s[it].isVowelLike() }
        if (!hasFurtherVowel) return 0
        if (c0 == 'g' && c1 == 'i') return 2
        if (c0 == 'q' && c1 == 'u') return 2
        return 0
    }

    /**
     * Returns whether [ch] would potentially trigger a Telex transformation.
     * Mainly for callers that want to decide when to refresh the composing buffer.
     */
    fun isTelexTriggerChar(ch: Char): Boolean {
        val lower = ch.lowercaseChar()
        return lower in "sfrxjz" || lower in "awedo"
    }
}
