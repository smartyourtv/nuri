package com.app.nuri.unstuck

import com.app.nuri.speech.SafetyIntent
import com.app.nuri.speech.SafetyIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The universal help entry (Shawn, 2026-10-17) — routing tests. This
 * test class lives in the SHARED test source set, so it runs against
 * BOTH flavors (testPublicDebugUnitTest + testPrivateDebugUnitTest):
 * the phrase routes identically in the private and public builds.
 */
class HelpEntryRoutingTest {
    @Test fun canonicalPhraseRoutesToHelpEntry() {
        assertEquals(SafetyIntent.HELP_REQUEST, SafetyIntentParser.parse("Can you help me with this?"))
    }

    @Test fun naturalVariantsRouteToHelpEntry() {
        assertEquals(SafetyIntent.HELP_REQUEST, SafetyIntentParser.parse("help me with this"))
        assertEquals(SafetyIntent.HELP_REQUEST, SafetyIntentParser.parse("I need help with something"))
        assertEquals(SafetyIntent.HELP_REQUEST, SafetyIntentParser.parse("can you look at this"))
        assertEquals(SafetyIntent.HELP_REQUEST, SafetyIntentParser.parse("Will you look at this for me?"))
    }

    @Test fun jumbledVariantsStillRoute() {
        // Tolerant matching (2026-10-17): intent, not the literal string.
        assertTrue(HelpEntry.isHelpEntry("um, help, this thing here"))
        assertTrue(HelpEntry.isHelpEntry("look at this please"))
        assertEquals(SafetyIntent.HELP_REQUEST, SafetyIntentParser.parse("help me with this thing"))
    }

    @Test fun bareHelpStaysEmergency() {
        assertEquals(SafetyIntent.EMERGENCY_TRIGGER, SafetyIntentParser.parse("help"))
        assertEquals(SafetyIntent.EMERGENCY_TRIGGER, SafetyIntentParser.parse("I need help"))
        assertEquals(SafetyIntent.EMERGENCY_TRIGGER, SafetyIntentParser.parse("emergency"))
    }

    @Test fun offerHasExactlyTwoPaths() {
        assertTrue(HelpEntry.OFFER_LINE.contains("tell me what it is"))
        assertTrue(HelpEntry.OFFER_LINE.contains("take a picture"))
    }

    @Test fun otherIntentsUnaffected() {
        assertEquals(SafetyIntent.WHERE_AM_I, SafetyIntentParser.parse("where am i"))
        assertEquals(SafetyIntent.MEDICATION_REMINDER, SafetyIntentParser.parse("my pills"))
    }
}
