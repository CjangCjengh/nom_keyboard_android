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

        // 1a. "w" does not need to be immediately preceded by o/u: it rewrites the most recent
        //     o/u/O/U in the current syllable. This lets the user type "nguoi" then "w" to get
        //     "nguơi" (rewrite the trailing 'o' to 'ơ'), or "uo" + "w" to get "ươ"
        //     (both the 'u' and 'o' of the "uo" diphthong get their horn marks).
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
        // the very end – e.g. "nguoi" + w should rewrite the 'o' in "ngu-o-i" to 'ơ'.
        for (i in composing.indices.reversed()) {
            val c = composing[i]
            if (!c.isLetter()) break
            when (c) {
                'o' -> return composing.substring(0, i) + 'ơ' + composing.substring(i + 1)
                'O' -> return composing.substring(0, i) + 'Ơ' + composing.substring(i + 1)
                'u' -> return composing.substring(0, i) + 'ư' + composing.substring(i + 1)
                'U' -> return composing.substring(0, i) + 'Ư' + composing.substring(i + 1)
            }
        }
        return null
    }

    /**
     * Pick the best vowel position in [s] to carry the tone mark.
     * Simplified Vietnamese orthography rules used here:
     *   - Prefer modified vowels ơ / ê / â / ô / ă when present.
     *   - Otherwise, if the word ends with a consonant, put the tone on the vowel
     *     immediately before the trailing consonant.
     *   - Otherwise (the last vowel is not followed by a consonant), put the tone
     *     on the second-to-last vowel (typical for diphthongs like "ia", "ua").
     *   - Fallback: the last vowel in the buffer.
     */
    private fun findToneTargetIndex(s: String): Int {
        // Priority: hat/horn/breve vowels
        val priority = "ơƠêÊâÂôÔăĂ"
        for ((i, ch) in s.withIndex().reversed()) {
            if (priority.contains(ch)) return i
        }
        // Collect all vowel positions
        val vowelIdx = ArrayList<Int>()
        for ((i, ch) in s.withIndex()) {
            if (ch.isVowelLike()) vowelIdx.add(i)
        }
        if (vowelIdx.isEmpty()) return -1
        if (vowelIdx.size == 1) return vowelIdx[0]
        val lastVowel = vowelIdx.last()
        val hasTrailingConsonant = lastVowel < s.length - 1
        return if (hasTrailingConsonant) lastVowel else vowelIdx[vowelIdx.size - 2]
    }

    /**
     * Returns whether [ch] would potentially trigger a Telex transformation.
     * Mainly for callers that want to decide when to refresh the composing buffer.
     */
    fun isTelexTriggerChar(ch: Char): Boolean {
        val lower = ch.lowercaseChar()
        return lower in "sfrxjz" || lower in "awed"
    }
}
