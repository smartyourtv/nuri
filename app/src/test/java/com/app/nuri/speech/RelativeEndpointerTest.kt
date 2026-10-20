package com.app.nuri.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V4/LL2/LL8: endpointing is relative to the utterance's own peak;
 * a whispered "yes" completes; the hangover protects the first
 * word; the safety max reports itself instead of burning silently.
 * Frames are 80 ms apart, like the live loop.
 */
class RelativeEndpointerTest {

    private fun feed(ep: RelativeEndpointer, rms: Float, fromMs: Long, toMs: Long): RelativeEndpointer.Result? {
        var result: RelativeEndpointer.Result? = null
        var t = fromMs
        while (t <= toMs) {
            result = ep.onFrame(rms, t) ?: result
            t += 80
        }
        return result
    }

    @Test fun quietShortAnswerCompletesByPause() {
        val ep = RelativeEndpointer()
        ep.start(0)
        // A whispered "yes": soft (0.03) for ~400 ms, then quiet.
        feed(ep, 0.03f, 0, 400)
        val result = feed(ep, 0.001f, 480, 3_000)
        assertNotNull(result)
        assertEquals(RelativeEndpointer.CloseReason.PAUSE, result!!.reason)
    }

    @Test fun loudRoomSpeechStillEndsRelativeToItsOwnPeak() {
        val ep = RelativeEndpointer()
        ep.start(0)
        // Noisy room: background at 0.02 (the old fixed threshold
        // would never close); speech peaks at 0.3; drops back to the
        // room's 0.02 — close threshold is max(0.3*0.25, floor)=0.075,
        // so room noise reads as silence FOR THIS utterance.
        feed(ep, 0.3f, 0, 1_200)
        val result = feed(ep, 0.02f, 1_280, 5_000)
        assertNotNull(result)
        assertEquals(RelativeEndpointer.CloseReason.PAUSE, result!!.reason)
    }

    @Test fun hangoverPreventsInstantClose() {
        val ep = RelativeEndpointer()
        ep.start(0)
        // Loud blip then silence INSIDE the hangover: no close.
        feed(ep, 0.4f, 0, 80)
        val early = feed(ep, 0.001f, 160, 700)
        assertNull(early)
    }

    @Test fun safetyMaxReportsItself() {
        val ep = RelativeEndpointer(safetyMaxMs = 2_000)
        ep.start(0)
        // Continuous loud sound: never pauses — the safety max must
        // close it AND say so.
        val result = feed(ep, 0.4f, 0, 5_000)
        assertNotNull(result)
        assertEquals(RelativeEndpointer.CloseReason.SAFETY_MAX, result!!.reason)
    }

    @Test fun noSpeechStillClosesAtTheSafetyMax() {
        val ep = RelativeEndpointer(safetyMaxMs = 1_600)
        ep.start(0)
        val result = feed(ep, 0.001f, 0, 3_000)
        assertNotNull(result)
        assertEquals(0L, result!!.speechMs)
    }
}
