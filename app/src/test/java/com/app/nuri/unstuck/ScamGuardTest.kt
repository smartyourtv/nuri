package com.app.nuri.unstuck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scam guard: the family safe word + message pattern screening. */
class ScamGuardTest {
    @Test fun safeWordMatchesCaseInsensitively() {
        assertTrue(SafeWord.matches("Bluebird", "bluebird"))
        assertTrue(SafeWord.matches("  bluebird  ", "Bluebird"))
    }

    @Test fun safeWordNeverMatchesWhenUnsetOrUnsaid() {
        assertFalse(SafeWord.matches("bluebird", null))
        assertFalse(SafeWord.matches("bluebird", ""))
        assertFalse(SafeWord.matches(null, "bluebird"))
        assertFalse(SafeWord.matches("wrongword", "bluebird"))
    }

    @Test fun emergencyLineAsksForTheSafeWordWhenSet() {
        assertTrue(SafeWord.emergencyCallLine(safeWordSet = true).contains("safe word"))
        assertTrue(SafeWord.emergencyCallLine(safeWordSet = false).contains("call them back"))
    }

    @Test fun giftCardUrgencyIsHighRisk() {
        assertEquals(ScamRisk.HIGH, ScamScreen.screen("URGENT: your account is suspended. Pay now with a gift card."))
    }

    @Test fun secrecyAloneIsSuspicious() {
        assertEquals(ScamRisk.SUSPICIOUS, ScamScreen.screen("This is between us — don't tell your family."))
    }

    @Test fun ordinaryMessageIsClean() {
        assertEquals(ScamRisk.NONE, ScamScreen.screen("Hi! Sunday dinner is at 5. Bring the salad if you can."))
    }

    @Test fun warningsNeverClaimCertaintyOnSuspicious() {
        val line = ScamScreen.warningLine(ScamRisk.SUSPICIOUS, "Shawn")!!
        assertTrue(line.contains("some signs of a scam"))
        assertTrue(line.contains("Shawn"))
        assertEquals(null, ScamScreen.warningLine(ScamRisk.NONE, "Shawn"))
    }
}
