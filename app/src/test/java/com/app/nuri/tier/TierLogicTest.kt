package com.app.nuri.tier
import com.app.nuri.config.NuriSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class TierLogicTest {
    @Test fun guidedClampsGamesOffEvenIfToggleOn() {
        val eff = EffectiveSettingsResolver.resolve(NuriSettings.Tier.GUIDED, PersonToggles(gamesAllowed = true))
        assertFalse(eff.gamesAllowed); assertFalse(eff.testingAllowed)
    }
    @Test fun guidedClampsEmotionToComfortingRange() {
        val eff = EffectiveSettingsResolver.resolve(NuriSettings.Tier.GUIDED, PersonToggles(emotionLevel = NuriSettings.EmotionLevel.FULL_HEARTED))
        assertEquals(NuriSettings.EmotionLevel.GENTLE, eff.emotionLevel)
    }
    @Test fun independentHonoursGamesToggleOff() {
        val eff = EffectiveSettingsResolver.resolve(NuriSettings.Tier.INDEPENDENT, PersonToggles(gamesAllowed = false))
        assertFalse(eff.gamesAllowed)
    }
    @Test fun supportedPersonToggle_gamesAllowed() {
        val eff = EffectiveSettingsResolver.resolve(NuriSettings.Tier.SUPPORTED, PersonToggles(gamesAllowed = true))
        assertTrue(eff.gamesAllowed); assertTrue(eff.slowerSpeech); assertTrue(eff.longerListenWindow)
    }
    @Test fun testingNeverAllowedInAnyTier() {
        NuriSettings.Tier.values().forEach { assertFalse(EffectiveSettingsResolver.resolve(it, PersonToggles()).testingAllowed) }
    }
}
