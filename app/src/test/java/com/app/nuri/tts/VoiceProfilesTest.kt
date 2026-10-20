package com.app.nuri.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V17 voice continuity: June is the DEFAULT (Shawn, 2026-10-19 —
 * June = Kokoro af_heart, sid 3), a stored choice always wins, and
 * nothing in resolution ever rewrites the stored value.
 */
class VoiceProfilesTest {

    @Test fun juneIsAfHeartSid3() {
        assertEquals(3, VoiceProfiles.JUNE.sid)
        assertEquals("af_heart", VoiceProfiles.JUNE.kokoroId)
    }

    @Test fun unsetResolvesToJune() {
        assertEquals(VoiceProfiles.JUNE, VoiceProfiles.resolve(null))
        assertEquals(VoiceProfiles.JUNE, VoiceProfiles.resolve(""))
        assertEquals(VoiceProfiles.JUNE, VoiceProfiles.resolve(VoiceProfiles.LEGACY_UNSET))
    }

    @Test fun storedChoiceAlwaysWins() {
        for (voice in VoiceProfiles.VOICES) {
            assertEquals(voice, VoiceProfiles.resolve(VoiceProfiles.storedValue(voice)))
        }
    }

    @Test fun garbageResolvesToJuneNotACrash() {
        assertEquals(VoiceProfiles.JUNE, VoiceProfiles.resolve("not-a-number"))
        assertEquals(VoiceProfiles.JUNE, VoiceProfiles.resolve("999"))
    }

    @Test fun catalogMatchesTheCuratedKokoroList() {
        // The same curated sids phone Scout ships (KOKORO_VOICES).
        assertEquals(listOf(3, 2, 9, 11, 15, 16, 17), VoiceProfiles.VOICES.map { it.sid })
        assertTrue(VoiceProfiles.VOICES.all { it.sampleLine.isNotBlank() })
    }

    @Test fun pickerSidsMatchTheV10PackSpeakerMap() {
        // The kokoro-multi-lang-v1_0 pack's own id2speaker table,
        // read byte-for-byte from the genuine archive's model.onnx
        // metadata (2026-10-20, v0.5.1 hotfix): 0 af_alloy,
        // 1 af_aoede, 2 af_bella, 3 af_heart, 4 af_jessica,
        // 5 af_kore, 6 af_nicole, 7 af_nova, 8 af_river, 9 af_sarah,
        // 10 af_sky, 11 am_adam, 12 am_echo, 13 am_eric, 14 am_fenrir,
        // 15 am_liam, 16 am_michael, 17 am_onyx, 18 am_puck,
        // 19 am_santa, 20 bf_alice, ... (54 speakers).
        // The v1.1 pack's table is a DIFFERENT (Chinese-edition)
        // map — 3 -> zf_001 there — which is why v0.5.1 pins the
        // picker against THIS one.
        val v10SpeakerMap = mapOf(
            3 to "af_heart",
            2 to "af_bella",
            9 to "af_sarah",
            11 to "am_adam",
            15 to "am_liam",
            16 to "am_michael",
            17 to "am_onyx"
        )
        assertEquals(v10SpeakerMap.keys, VoiceProfiles.VOICES.map { it.sid }.toSet())
        for (voice in VoiceProfiles.VOICES) {
            assertEquals(v10SpeakerMap[voice.sid], voice.kokoroId)
        }
    }

    @Test fun speedStaysInsideTheSafeBand() {
        for (voice in VoiceProfiles.VOICES) {
            for (slower in listOf(false, true)) {
                val speed = VoiceProfiles.speedFor(voice, 1.05f, slower)
                assertTrue(speed in 0.6f..1.3f)
            }
        }
        // Ilona profile (v0.5.2): the natural rate, owner-picked by
        // ear on 2026-10-20 (renders at 0.92 / 0.95 / 1.0 — he
        // chose 1.0). Slower only on the person's explicit request.
        val normal = VoiceProfiles.speedFor(VoiceProfiles.JUNE, 1f, slowerSpeech = false)
        val slower = VoiceProfiles.speedFor(VoiceProfiles.JUNE, 1f, slowerSpeech = true)
        assertEquals(1.0f, normal, 0.0001f)
        assertEquals(0.92f, slower, 0.0001f)
        assertTrue(slower < normal)
    }

    @Test fun slowerSpeechAppliesExactlyOnceOnTheLivePath() {
        // The live path (IlonaSpeaker.speakBlocking) composes
        // hintsFor's RAW rate with speedFor — the TtsPipeline
        // stacking question, settled in the v0.5.2 audit:
        // EmotionProsody.buildRequest's own 0.92 bake is never on
        // this path (buildRequest has no callers), so the toggle
        // slows speech exactly once, never to ~0.85.
        val hints = EmotionProsody.hintsFor(
            com.app.nuri.config.NuriSettings.EmotionLevel.WARM,
            com.app.nuri.config.NuriSettings.Tier.SUPPORTED
        )
        val live = VoiceProfiles.speedFor(VoiceProfiles.JUNE, hints.rateMultiplier, slowerSpeech = true)
        assertEquals(1.0f * hints.rateMultiplier * 0.92f, live, 0.0001f)
        assertTrue(live > 0.80f) // a double application would land at ~0.80 or below
    }
}
