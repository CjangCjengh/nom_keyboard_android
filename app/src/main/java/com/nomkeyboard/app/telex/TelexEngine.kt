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
     */
    fun apply(composing: String, ch: Char): String {
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

        // 1a. "w" does not need to be immediately preceded by o/u: it rewrites the most recent
        //     o/u/O/U in the current syllable. See [rewriteHornForW] for the full rules
        //     (including the "uo" -> "ươ" diphthong special case, also applied when only the
        //     'o' is the most-recent target).
        //
        //     If there is no o/u to rewrite (e.g. the syllable is "ng" + 'w'), we fall back to
        //     appending a standalone "ư" – matching the behaviour of the standalone-'w'
        //     shortcut above.
        if (ch == 'w' || ch == 'W') {
            val rewritten = rewriteHornForW(composing)
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
        // Only trigger if the buffer already contains a vowel to attach the tone to
        if (tone != null && composing.any { it.isVowelLike() }) {
            val idx = findToneTargetIndex(composing)
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

        // 3. Default: append the character as-is
        return composing + ch
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
     * Apply the horn diacritic in response to the Telex "w" trigger. This covers three cases:
     *   - The syllable ends with the diphthong "uo" / "uO" / "Uo" / "UO": both vowels receive
     *     the horn (ươ or its uppercase variants).
     *   - The syllable contains a plain o/O/u/U somewhere (not necessarily in the last position):
     *     only the most recent one is rewritten (handles "nguoi" + w -> "nguơi",
     *     "quang" -> nothing, etc.).
     * Returns null if no rewrite is applicable and the caller should fall back to normal rules.
     *
     * The case of the resulting horn vowel is derived from the scanned character itself, so the
     * trigger key's own case ('w' vs 'W') is irrelevant here.
     */
    private fun rewriteHornForW(composing: String): String? {
        // Case 1: consecutive "uo" at the tail – rewrite both to ươ.
        if (composing.length >= 2) {
            val c1 = composing[composing.length - 2]
            val c2 = composing[composing.length - 1]
            val pair = "${c1}${c2}".lowercase()
            if (pair == "uo") {
                val newC1 = if (c1.isUpperCase()) 'Ư' else 'ư'
                val newC2 = if (c2.isUpperCase()) 'Ơ' else 'ơ'
                return composing.dropLast(2) + newC1 + newC2
            }
        }

        // Case 2: scan backwards for the most recent o/u that is still inside the current
        // syllable (we stop at a non-letter boundary). The target vowel does not have to be at
        // the very end – e.g. "nguoi" + w should rewrite the "uo" part of "ng-u-o-i" to "ươ",
        // yielding "ngươi". When the found target is 'o'/'O' AND the character right before
        // it is 'u'/'U', we add the horn to both (they form a single diphthong).
        for (i in composing.indices.reversed()) {
            val c = composing[i]
            if (!c.isLetter()) break
            when (c) {
                'o', 'O' -> {
                    // Check whether the preceding character is u/U – if so, apply the horn
                    // diacritic to both characters (e.g. "nguoi" + w -> "ngươi").
                    if (i > 0) {
                        val prev = composing[i - 1]
                        if (prev == 'u' || prev == 'U') {
                            val newPrev = if (prev.isUpperCase()) 'Ư' else 'ư'
                            val newCur = if (c.isUpperCase()) 'Ơ' else 'ơ'
                            return composing.substring(0, i - 1) + newPrev + newCur +
                                    composing.substring(i + 1)
                        }
                    }
                    val repl = if (c.isUpperCase()) 'Ơ' else 'ơ'
                    return composing.substring(0, i) + repl + composing.substring(i + 1)
                }
                'u' -> return composing.substring(0, i) + 'ư' + composing.substring(i + 1)
                'U' -> return composing.substring(0, i) + 'Ư' + composing.substring(i + 1)
            }
        }
        return null
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
     *   4. Otherwise (open syllable, i.e. last char is a vowel), put the tone on the
     *      second-to-last vowel – this is the modern Vietnamese style for diphthongs like
     *      "oa", "oe", "uy" ("hoà" not "hòa" in open syllables, but "hoá" style is what
     *      modern orthography picks for "hóa"... we follow the modern style: second-to-last
     *      vowel).
     *   5. Fallback: the last vowel in the buffer.
     */
    private fun findToneTargetIndex(s: String): Int {
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
        return if (hasTrailingConsonant) lastVowel else vowelIdx[vowelIdx.size - 2]
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
