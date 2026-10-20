package com.app.nuri.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LL6 + V1: the session pattern and the 800 ms wake hangover. */
class ListenSessionTest {

    @Test fun wakeOpensA90SecondWindow() {
        val m = ListenSessionMachine()
        assertFalse(m.isOpen())
        m.onWake(1_000)
        assertTrue(m.isOpen())
        assertFalse(m.tick(1_000 + 89_999))
        assertTrue(m.isOpen())
        assertTrue(m.tick(1_000 + 90_000))
        assertFalse(m.isOpen())
    }

    @Test fun activityExtendsTheWindow() {
        val m = ListenSessionMachine()
        m.onWake(0)
        m.onActivity(80_000)
        assertFalse(m.tick(100_000))
        assertTrue(m.tick(80_000 + 90_000))
    }

    @Test fun keepListeningHoldsTenMinutes() {
        val m = ListenSessionMachine()
        m.onWake(0)
        assertTrue(m.onPhrase("keep listening", 10_000))
        assertEquals(ListenSessionMachine.State.HOLD, m.state)
        assertFalse(m.tick(10_000 + 599_999))
        assertTrue(m.tick(10_000 + 600_000))
    }

    @Test fun thatsAllEndsTheSession() {
        val m = ListenSessionMachine()
        m.onWake(0)
        assertTrue(m.onPhrase("That's all.", 5_000))
        assertFalse(m.isOpen())
    }

    @Test fun ordinarySpeechIsNotConsumed() {
        val m = ListenSessionMachine()
        m.onWake(0)
        assertFalse(m.onPhrase("what time is it", 5_000))
        assertTrue(m.isOpen())
    }

    @Test fun hangoverCoversTheFirst800ms() {
        val m = ListenSessionMachine()
        m.onWake(10_000)
        assertTrue(m.inWakeHangover(10_799))
        assertFalse(m.inWakeHangover(10_800))
    }

    @Test fun holdPhrasesAreTolerant() {
        assertTrue(SessionPhrases.isHoldPhrase("Stay with me"))
        assertTrue(SessionPhrases.isHoldPhrase("please keep listening"))
        assertFalse(SessionPhrases.isHoldPhrase("keep going"))
    }
}
