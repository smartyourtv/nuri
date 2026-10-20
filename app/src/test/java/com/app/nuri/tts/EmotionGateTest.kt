package com.app.nuri.tts

import com.app.nuri.config.NuriSettings.EmotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * V10's code gate: at URGENT or SERIOUS, nothing plays — at ANY
 * level, with ANY random seed, with a full catalog. And the breath
 * law: no decorative breath during urgent speech, ever.
 */
class EmotionGateTest {

    private val fullCatalog = EmotionGate.SoundKind.values()
        .filter { it != EmotionGate.SoundKind.NONE }
        .map { EmotionGate.Entry(id = it.name, kind = it) }

    @Test fun urgentAndSeriousAreAlwaysSilent() {
        for (level in EmotionLevel.values()) {
            for (seed in 0..49) {
                assertNull(
                    EmotionGate.decide(EmotionGate.Situation.URGENT, level, fullCatalog, emptyList(), Random(seed))
                )
                assertNull(
                    EmotionGate.decide(EmotionGate.Situation.SERIOUS, level, fullCatalog, emptyList(), Random(seed))
                )
            }
        }
    }

    @Test fun calmAndGentleNeverLaugh() {
        for (level in listOf(EmotionLevel.CALM, EmotionLevel.GENTLE)) {
            for (seed in 0..49) {
                val picked = EmotionGate.decide(EmotionGate.Situation.FUNNY, level, fullCatalog, emptyList(), Random(seed))
                assertNull(picked)
            }
        }
    }

    @Test fun warmChucklesAtFunnyMoments() {
        val picked = EmotionGate.decide(EmotionGate.Situation.FUNNY, EmotionLevel.WARM, fullCatalog, emptyList(), Random(1))
        assertNotNull(picked)
        assertEquals(EmotionGate.SoundKind.CHUCKLE, picked!!.kind)
    }

    @Test fun fullHeartedLaughsFully() {
        val picked = EmotionGate.decide(EmotionGate.Situation.FUNNY, EmotionLevel.FULL_HEARTED, fullCatalog, emptyList(), Random(1))
        assertEquals(EmotionGate.SoundKind.FULL_LAUGH, picked!!.kind)
    }

    @Test fun comfortGetsASighOrSoftBreath() {
        val warm = EmotionGate.decide(EmotionGate.Situation.COMFORT, EmotionLevel.WARM, fullCatalog, emptyList(), Random(1))
        assertEquals(EmotionGate.SoundKind.SIGH, warm!!.kind)
        val calm = EmotionGate.decide(EmotionGate.Situation.COMFORT, EmotionLevel.CALM, fullCatalog, emptyList(), Random(1))
        assertEquals(EmotionGate.SoundKind.BREATH_SOFT, calm!!.kind)
    }

    @Test fun neverTheSameSoundTwiceInARow() {
        val catalog = listOf(
            EmotionGate.Entry("a", EmotionGate.SoundKind.CHUCKLE),
            EmotionGate.Entry("b", EmotionGate.SoundKind.CHUCKLE),
            EmotionGate.Entry("c", EmotionGate.SoundKind.CHUCKLE)
        )
        for (seed in 0..99) {
            val first = EmotionGate.decide(EmotionGate.Situation.FUNNY, EmotionLevel.WARM, catalog, emptyList(), Random(seed))!!
            val second = EmotionGate.decide(EmotionGate.Situation.FUNNY, EmotionLevel.WARM, catalog, listOf(first.id), Random(seed + 1000))!!
            assertTrue("seed $seed repeated ${first.id}", first.id != second.id)
        }
    }

    @Test fun missingKindStepsDownNeverUp() {
        // Only a chuckle exists: a full-laugh moment degrades to it.
        val chuckleOnly = listOf(EmotionGate.Entry("c1", EmotionGate.SoundKind.CHUCKLE))
        val picked = EmotionGate.decide(EmotionGate.Situation.FUNNY, EmotionLevel.FULL_HEARTED, chuckleOnly, emptyList(), Random(3))
        assertEquals(EmotionGate.SoundKind.CHUCKLE, picked!!.kind)
        // Empty catalog: silence, not an error.
        assertNull(EmotionGate.decide(EmotionGate.Situation.FUNNY, EmotionLevel.WARM, emptyList(), emptyList(), Random(3)))
    }

    @Test fun breathLawUrgentGetsNone() {
        for (seed in 0..19) {
            assertEquals(
                EmotionGate.SoundKind.NONE,
                EmotionGate.breathBetween(afterLaughter = true, serious = false, urgent = true, random = Random(seed))
            )
        }
        assertEquals(
            EmotionGate.SoundKind.BREATH_SOFT,
            EmotionGate.breathBetween(afterLaughter = false, serious = true, urgent = false, random = Random(1))
        )
    }

    @Test fun bankManifestParsesAndFeedsTheGate() {
        val csv = """
            id,kind,file,durationMs,insertSafe
            june-01,CHUCKLE,sample01.wav,640,true
            june-02,FULL_LAUGH,sample02.wav,1450,false
            june-03,NONE,bad.wav,10,true
            broken-line
        """.trimIndent()
        val entries = EmotionBank.parseManifest(csv)
        assertEquals(2, entries.size)
        assertEquals(EmotionGate.SoundKind.CHUCKLE, entries[0].kind)
        assertTrue(entries[0].insertSafe)
        val gateCatalog = EmotionBank.gateCatalog(entries)
        val picked = EmotionGate.decide(EmotionGate.Situation.FUNNY, EmotionLevel.WARM, gateCatalog, emptyList(), Random(0))
        assertEquals("june-01", picked!!.id)
    }
}
