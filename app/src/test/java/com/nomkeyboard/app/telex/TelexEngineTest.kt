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

    // ---- bare gi-/qu- onset glide + w ----------------------------------------
    //
    // The reported bug: "gi" + w must give "giư" (needed for the Nôm readings
    // giương / giường / giưòng), not the literal "giw". The trailing i/u of a
    // bare "gi"/"qu" syllable belongs to the ONSET glide, so the syllable has no
    // rhyme vowel yet and the w supplies ư – exactly like the pure-consonant
    // onset case ("ng" + w -> "ngư").

    @Test
    fun bareGlideOnsetWSuppliesUhorn() {
        assertEquals("giư", apply("gi", 'w'))
        assertEquals("quư", apply("qu", 'w'))
        assertEquals("Giư", apply("Gi", 'w'))
        assertEquals("GIƯ", apply("GI", 'W'))
    }

    @Test
    fun bareGlideOnsetWSecondPressUndoes() {
        // wouldBeStandaloneW must classify "gi"/"qu" as standalone-w so the IME
        // sets the flag and a second 'w' cancels the shortcut back to a literal w.
        assertEquals(true, TelexEngine.wouldBeStandaloneW("gi", 'w'))
        assertEquals(true, TelexEngine.wouldBeStandaloneW("qu", 'w'))
        assertEquals("giw", TelexEngine.apply("giư", 'w', lastWasStandaloneW = true))
        assertEquals("quw", TelexEngine.apply("quư", 'w', lastWasStandaloneW = true))
    }

    @Test
    fun glideBehaviourUnchangedWhenRhymeExists() {
        // A real rhyme vowel after the glide keeps the old cluster behaviour.
        assertEquals("giă", apply("gia", 'w'))
        assertEquals("quă", apply("qua", 'w'))
        assertEquals("giơ", apply("gio", 'w'))
        // Non-glide syllables merely ending in i/u keep the literal w.
        assertEquals("ngiw", apply("ngi", 'w'))
        assertEquals("ginw", apply("gin", 'w'))
        // A toned glide vowel means the user has moved on: literal w.
        assertEquals("gìw", apply("gì", 'w'))
    }
}
