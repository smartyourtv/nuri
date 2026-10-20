package com.app.nuri.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LL5: the speakable rules are the laptop's, proven by ear there —
 * "nine one one", year words, markdown never spoken, medicine units
 * expanded with names untouched.
 */
class SpeakableTextTest {

    @Test fun emergencyNumberIsSpelledDigitByDigit() {
        assertEquals("Call nine one one now.", SpeakableText.normalize("Call 911 now."))
    }

    @Test fun yearsAreYearWords() {
        assertEquals("Born in nineteen ninety-two.", SpeakableText.normalize("Born in 1992."))
        assertEquals("It is twenty twenty-six.", SpeakableText.normalize("It is 2026."))
        assertEquals("In two thousand, we moved.", SpeakableText.normalize("In 2000, we moved."))
        assertEquals("Since two thousand five.", SpeakableText.normalize("Since 2005."))
    }

    @Test fun markdownNeverReachesTheEar() {
        val out = SpeakableText.normalize("## Your **medicine** is `ready`")
        assertFalse(out.contains("*"))
        assertFalse(out.contains("`"))
        assertFalse(out.contains("#"))
        assertTrue(out.contains("medicine"))
    }

    @Test fun multiplicationStarReadsAsTimes() {
        assertEquals("Take 2 times 3 daily.", SpeakableText.normalize("Take 2*3 daily."))
    }

    @Test fun doctorVersusDriveByContext() {
        assertEquals("See doctor Smith on Tuesday.", SpeakableText.normalize("See Dr. Smith on Tue."))
        assertEquals("Elm drive is quiet.", SpeakableText.normalize("Elm Dr. is quiet."))
    }

    @Test fun medicineUnitsExpandButNamesStayVerbatim() {
        val out = SpeakableText.normalize("Take metoprolol 50 mg and fluticasone 50 mcg.")
        assertTrue(out.contains("metoprolol 50 milligrams"))
        assertTrue(out.contains("fluticasone 50 micrograms"))
    }

    @Test fun decimalDosesAreSpelled() {
        assertEquals("Take zero point five milligrams.", SpeakableText.normalize("Take 0.5 mg."))
    }

    @Test fun phoneNumbersReadDigitByDigitInGroups() {
        val out = SpeakableText.normalize("Call 555-234-5678 today.")
        assertTrue(out.contains("five five five, two three four, five six seven eight"))
    }

    @Test fun ordinalsForDates() {
        assertEquals("October third", SpeakableText.normalize("October 3rd"))
    }

    @Test fun percentAndDegrees() {
        assertEquals("It is 72 degrees Fahrenheit and 10 percent humidity.",
            SpeakableText.normalize("It is 72°F and 10% humidity."))
    }

    @Test fun sentencesSplitForPipelining() {
        val parts = SpeakableText.sentences("Hello there. How are you? I am fine!")
        assertEquals(listOf("Hello there.", "How are you?", "I am fine!"), parts)
        assertEquals(listOf("One fragment"), SpeakableText.sentences("One fragment"))
        assertEquals(emptyList<String>(), SpeakableText.sentences("  "))
    }
}
