package com.app.nuri.tier

import com.app.nuri.config.NuriSettings
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sweep #45: tier-slide suggestion — framed as fit, caregiver decides. */
class TierSlideTest {
    @Test fun suggestsGentlerTierAfterRepeatedStuckEpisodes() {
        val suggestion = TierSlideAdvisor.suggestionFor(NuriSettings.Tier.SUPPORTED, recentStuckOrAskedCount = 7)
        assertTrue(suggestion!!.contains("make this easier"))
        assertTrue(suggestion.contains("Guided"))
        assertTrue(suggestion.contains("Only the caregiver changes this"))
    }

    @Test fun quietBelowThreshold() {
        assertNull(TierSlideAdvisor.suggestionFor(NuriSettings.Tier.SUPPORTED, 2))
    }

    @Test fun neverSuggestsPastGuided() {
        assertNull(TierSlideAdvisor.suggestionFor(NuriSettings.Tier.GUIDED, 99))
    }

    @Test fun independentSuggestsSupported() {
        assertTrue(TierSlideAdvisor.suggestionFor(NuriSettings.Tier.INDEPENDENT, 8)!!.contains("Supported"))
    }
}
