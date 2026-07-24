package com.nomkeyboard.app.telex

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the long-distance hat rule (aa/ee/oo typed across a consonant,
 * e.g. "quoc" + o -> "quôc").
 *
 * The bug: after the double-press tone-undo escape ("re" + s -> "ré" + s -> "res"),
 * the trailing 's' has broken out of the Telex frame, yet typing 'e' still reached back
 * and hat-upgraded the vowel: "res" + e gave "rês" instead of the literal "rese".
 * Root cause: [TelexEngine]'s long-distance hat scan never checked that the letters
 * after the target vowel still form a legal Vietnamese syllable ending – and 's' is
 * not a legal coda. The fix gates the hat on that check.
 */
class TelexEngineTest {

    private fun apply(composing: String, ch: Char) = TelexEngine.apply(composing, ch)

    // ---- the reported bug -------------------------------------------------

    @Test
    fun hatDoesNotFirePastToneUndoEscape() {
        assertEquals("rese", apply("res", 'e'))
        assertEquals("rasa", apply("ras", 'a'))
        assertEquals("roso", apply("ros", 'o'))
        assertEquals("tese", apply("tes", 'e'))
    }

    @Test
    fun hatDoesNotFirePastToneUndoEscapeUppercase() {
        assertEquals("RESE", apply("RES", 'E'))
    }

    @Test
    fun hatDoesNotFireWhenVowelFollowsConsonant() {
        assertEquals("retae", apply("reta", 'e'))
        assertEquals("teone", apply("teon", 'e')) // "on" is not a coda
        assertEquals("rede", apply("red", 'e'))   // 'd' is not a coda
    }

    // ---- behaviours that must keep working ---------------------------------

    @Test
    fun longDistanceHatStillWorksWithLegalCodas() {
        assertEquals("quôc", apply("quoc", 'o'))
        assertEquals("toân", apply("toan", 'a'))
        assertEquals("tên", apply("ten", 'e'))
        assertEquals("rêng", apply("reng", 'e'))
        assertEquals("rêt", apply("ret", 'e'))
        assertEquals("rêm", apply("rem", 'e'))
        assertEquals("rêch", apply("rech", 'e'))
    }

    @Test
    fun longDistanceHatStillWorksOnOpenSyllables() {
        assertEquals("để", apply("đẻ", 'e'))  // toned target, tone preserved
        assertEquals("trêo", apply("treo", 'e')) // target inside vowel cluster
    }

    @Test
    fun adjacentMergeAndToneUndoUnchanged() {
        assertEquals("rê", apply("re", 'e'))
        assertEquals("câ", apply("ca", 'a'))
        assertEquals("ré", apply("re", 's'))
        assertEquals("res", apply("ré", 's')) // the escape itself
    }

    // ---- mid-syllable hat undo (double-press restore past a coda) -----------

    @Test
    fun hatUndoWorksPastCoda() {
        assertEquals("rete", apply("rêt", 'e'))
        assertEquals("quoco", apply("quôc", 'o'))
        assertEquals("toana", apply("toân", 'a'))
        assertEquals("eme", apply("êm", 'e'))
        assertEquals("renge", apply("rêng", 'e'))
        assertEquals("treoe", apply("trêo", 'e'))
    }

    @Test
    fun hatUndoWorksPastCodaUppercase() {
        assertEquals("RETE", apply("RÊT", 'E'))
    }

    @Test
    fun tonedHatVowelIsNotReverted() {
        // A toned modified vowel means the user has moved on to tone editing;
        // the hat trigger must not destroy the tone.
        assertEquals("rếte", apply("rết", 'e'))
        assertEquals("quốco", apply("quốc", 'o'))
    }

    @Test
    fun adjacentHatUndoUnchanged() {
        assertEquals("ree", apply("rê", 'e'))
        assertEquals("too", apply("tô", 'o'))
        assertEquals("caa", apply("câ", 'a'))
    }
}
