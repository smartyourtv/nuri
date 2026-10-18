package com.app.nuri.unstuck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tolerant intent matching (Shawn, 2026-10-17): jumbled, fragmented,
 * wrong-word phrasings must still land on the right helper — or on the
 * never-dead-end ladder, never on a flat "I don't understand".
 */
class TolerantMatchTest {
    private fun match(text: String): TolerantMatch.Match? = TolerantMatch.match(text, HelperCatalogDataB.combined)

    @Test fun exactTriggerStillMatchesStrongly() {
        val m = match("did i take my pills")
        assertEquals(9, m?.helper?.number)
        assertEquals(TolerantMatch.Confidence.STRONG, m?.confidence)
    }

    @Test fun fragmentAboutPillsFindsTheMedHelper() {
        // "the… the blue thing… the pills" — topic-only fragment.
        val m = match("the the blue thing the pills")
        assertNotNull(m)
        assertEquals(9, m?.helper?.number)
    }

    @Test fun wrongWordSubstitutionsMatch() {
        // "television" for TV, "medicine" for pills.
        assertEquals(9, match("did i take my medicine this morning")?.helper?.number)
        assertEquals(1, match("the television is just a black screen")?.helper?.number)
    }

    @Test fun jumbledTalkBackComplaintMatches() {
        val m = match("my phone talks everything and the taps don't work right")
        assertEquals(70, m?.helper?.number)
    }

    @Test fun jumbledAlarmComplaintMatches() {
        assertEquals(83, match("alarm morning no ring why")?.helper?.number)
    }

    @Test fun scamFragmentMatches() {
        val m = match("grandson jail money don't tell")
        assertNotNull(m)
        assertEquals(21, m?.helper?.number)
    }

    @Test fun medicareLetterFragmentMatches() {
        assertEquals(16, match("letter medicare owe money")?.helper?.number)
    }

    @Test fun dogOutMatchesEscalateHelper() {
        assertEquals(47, match("dog got out the gate")?.helper?.number)
    }

    @Test fun wordOrderDoesNotMatter() {
        val a = match("pills take did i my")
        assertEquals(9, a?.helper?.number)
    }

    @Test fun gibberishYieldsNoMatchButLadderExists() {
        val m = match("flibberty gibbet snorkel")
        assertTrue(m == null || m.confidence == TolerantMatch.Confidence.NONE)
        // The never-dead-end ladder still offers evergreen choices.
        assertEquals(4, NoMatchLadder.evergreenChoices().size)
        assertTrue(NoMatchLadder.NARROWING_QUESTION.contains("narrow it down"))
    }

    @Test fun fairMatchesComeWithAMirrorLine() {
        val matches = TolerantMatch.matchAll("sound gone phone", HelperCatalogDataB.combined)
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.first().mirrorLine().contains("Is that the one?"))
    }

    @Test fun matchingIsDeterministic() {
        // Patience rule: the same question gets the same answer every time.
        val a = match("where are my glasses")
        val b = match("where are my glasses")
        assertEquals(a?.helper?.number, b?.helper?.number)
        assertEquals(14, a?.helper?.number)
    }
}
