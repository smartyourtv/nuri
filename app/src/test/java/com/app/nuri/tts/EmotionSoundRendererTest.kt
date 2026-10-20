package com.app.nuri.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Invariants of the ported shaped renderer (phone Scout lineage) —
 * the route for non-June voices. These tests pin the RENDERER's
 * mechanics (pulse plans, lengths, no-input safety), not its
 * quality: quality is judged by ear (V19), never by a unit test.
 */
class EmotionSoundRendererTest {

    @Test fun laughPlanStaysInsideItsRanges() {
        for (seed in 0..99) {
            val plan = EmotionSoundRenderer.planLaugh(EmotionSoundRenderer.LaughKind.CHUCKLE, Random(seed))
            assertTrue(plan.pulseCount in 2..3)
            val full = EmotionSoundRenderer.planLaugh(EmotionSoundRenderer.LaughKind.FULL_LAUGH, Random(seed))
            assertTrue(full.pulseCount in 4..6)
        }
    }

    @Test fun emptyFragmentRendersEmptyNotNoise() {
        assertEquals(0, EmotionSoundRenderer.renderLaugh(FloatArray(0), 24_000, EmotionSoundRenderer.LaughKind.CHUCKLE, Random(1)).size)
        assertEquals(0, EmotionSoundRenderer.renderSigh(FloatArray(0), 24_000, Random(1)).size)
    }

    @Test fun sighIsASecondOrSo() {
        val fragment = FloatArray(12_000) { 0.1f }
        for (seed in 0..19) {
            val sigh = EmotionSoundRenderer.renderSigh(fragment, 24_000, Random(seed))
            val seconds = sigh.size / 24_000.0
            assertTrue("sigh $seconds s out of range", seconds in 1.05..1.45)
        }
    }

    @Test fun breathsVaryAndStayQuiet() {
        val a = BreathSoundGenerator.generateBreath(24_000, durationMs = 200)
        val b = BreathSoundGenerator.generateBreath(24_000, durationMs = 320)
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        assertTrue("breath lengths differ by design", a.size != b.size)
        val peak = a.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("breath peak $peak too loud", peak < 3_300) // ~-18 dBFS ceiling in 16-bit PCM
    }
}
