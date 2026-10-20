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

    @Test fun speedStaysInsideTheSafeBand() {
        for (voice in VoiceProfiles.VOICES) {
            for (slower in listOf(false, true)) {
                val speed = VoiceProfiles.speedFor(voice, 1.05f, slower)
                assertTrue(speed in 0.6f..1.3f)
            }
        }
        // Ilona profile: slower than natural, slower still on request.
        val normal = VoiceProfiles.speedFor(VoiceProfiles.JUNE, 1f, slowerSpeech = false)
        val slower = VoiceProfiles.speedFor(VoiceProfiles.JUNE, 1f, slowerSpeech = true)
        assertTrue(normal < 1f)
        assertTrue(slower < normal)
    }
}
