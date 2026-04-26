package com.nomkeyboard.app.dict

/**
 * Utility for normalising Vietnamese tone-mark placement between the "old" and
 * "new" orthographic styles.
 *
 * The bundled Hán Nôm dictionary stores every rising/open-diphthong syllable in
 * the NEW style (e.g. `hoá`, `hoà`, `thuý`) – the tone mark sits on the second
 * vowel of `oa / oe / uy` sequences. Users who learned Vietnamese with the
 * traditional/old style type the same word as `hóa`, `hòa`, `thúy` (tone mark on
 * the first vowel). The lenient-match path hides this discrepancy by stripping
 * diacritics altogether; the strict-match path needs an explicit normaliser so
 * that old-style user input still hits the dictionary without collapsing every
 * tonal distinction.
 *
 * [normaliseToNewStyle] walks the string and, whenever it finds a tone-carrying
 * `o` or `u` immediately followed by an unmarked `a / e / y` (or `A / E / Y`)
 * in an OPEN syllable (no consonant following the vowel cluster in the same
 * syllable), it moves the tone mark onto the second vowel. Case is preserved.
 *
 * Examples:
 *   hóa  -> hoá
 *   hòa  -> hoà
 *   thúy -> thuý
 *   khỏe -> khoẻ
 *   Hóa  -> Hoá
 *
 * Inputs with no rising-diphthong patterns (e.g. `quan`, `kiều`, `bình`) are
 * returned verbatim – this is explicitly a no-op for anything outside the
 * `oa / oe / uy` family.
 */
object ToneStyle {
    // Tone mark -> (base vowel, combining-mark codepoint). We use the NFC
    // precomposed forms directly so we don't have to fight Unicode normalisation.

    // Maps an old-style tone-carrying vowel (tone on o/u) to the pair
    // (plain o/u, tone name). Tone name is a single sentinel char we use to
    // rebuild the new-style precomposed character.
    private val OLD_VOWELS = mapOf(
        // o family
        'ó' to ('o' to 'A'),  // acute
        'ò' to ('o' to 'G'),  // grave
        'ỏ' to ('o' to 'H'),  // hook
        'õ' to ('o' to 'T'),  // tilde
        'ọ' to ('o' to 'D'),  // dot below
        'Ó' to ('O' to 'A'),
        'Ò' to ('O' to 'G'),
        'Ỏ' to ('O' to 'H'),
        'Õ' to ('O' to 'T'),
        'Ọ' to ('O' to 'D'),
        // u family
        'ú' to ('u' to 'A'),
        'ù' to ('u' to 'G'),
        'ủ' to ('u' to 'H'),
        'ũ' to ('u' to 'T'),
        'ụ' to ('u' to 'D'),
        'Ú' to ('U' to 'A'),
        'Ù' to ('U' to 'G'),
        'Ủ' to ('U' to 'H'),
        'Ũ' to ('U' to 'T'),
        'Ụ' to ('U' to 'D'),
    )

    // Target vowel lookup: (plainTarget, toneName) -> tone-carrying vowel.
    private val NEW_VOWELS = mapOf(
        // a family
        ('a' to 'A') to 'á', ('a' to 'G') to 'à', ('a' to 'H') to 'ả',
        ('a' to 'T') to 'ã', ('a' to 'D') to 'ạ',
        ('A' to 'A') to 'Á', ('A' to 'G') to 'À', ('A' to 'H') to 'Ả',
        ('A' to 'T') to 'Ã', ('A' to 'D') to 'Ạ',
        // e family
        ('e' to 'A') to 'é', ('e' to 'G') to 'è', ('e' to 'H') to 'ẻ',
        ('e' to 'T') to 'ẽ', ('e' to 'D') to 'ẹ',
        ('E' to 'A') to 'É', ('E' to 'G') to 'È', ('E' to 'H') to 'Ẻ',
        ('E' to 'T') to 'Ẽ', ('E' to 'D') to 'Ẹ',
        // y family
        ('y' to 'A') to 'ý', ('y' to 'G') to 'ỳ', ('y' to 'H') to 'ỷ',
        ('y' to 'T') to 'ỹ', ('y' to 'D') to 'ỵ',
        ('Y' to 'A') to 'Ý', ('Y' to 'G') to 'Ỳ', ('Y' to 'H') to 'Ỷ',
        ('Y' to 'T') to 'Ỹ', ('Y' to 'D') to 'Ỵ',
    )

    // Plain / tone-bearing tail vowels we are willing to move the mark ONTO.
    // Intentionally does NOT include i/o/u – only a / e / y because those are
    // the second member of rising diphthongs `oa`, `oe`, `uy`.
    private val TAIL_BASE = setOf('a', 'e', 'y', 'A', 'E', 'Y')

    // Characters considered part of the same syllable's coda. If the very next
    // character after the tail vowel is a letter, the syllable is CLOSED and
    // new-style keeps the tone on the first vowel (e.g. `thuận`, `toán`), so we
    // skip normalisation. Anything non-letter (space, punctuation, end-of-string)
    // ends the syllable and we do normalise.
    private fun isLetter(ch: Char) = ch.isLetter()

    fun normaliseToNewStyle(s: String): String {
        if (s.isEmpty()) return s
        // Quick-reject: if the string has none of our old-style vowels, return as-is.
        if (s.none { OLD_VOWELS.containsKey(it) }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val info = OLD_VOWELS[ch]
            if (info == null) { sb.append(ch); i++; continue }
            val (base, tone) = info
            // Need a tail vowel right after.
            if (i + 1 >= s.length) { sb.append(ch); i++; continue }
            val tail = s[i + 1]
            if (tail !in TAIL_BASE) { sb.append(ch); i++; continue }
            // Need the syllable to be OPEN: no letter directly after the tail
            // vowel. (Letters mean a coda consonant -> keep old position.)
            val afterTail = if (i + 2 < s.length) s[i + 2] else ' '
            if (isLetter(afterTail)) { sb.append(ch); i++; continue }
            val newTail = NEW_VOWELS[tail to tone]
            if (newTail == null) { sb.append(ch); i++; continue }
            sb.append(base)
            sb.append(newTail)
            i += 2
        }
        return sb.toString()
    }
}
