package com.app.nuri.tier

import com.app.nuri.config.NuriSettings

/**
 * Tier-slide suggestion (brief tier design; sweep #45): Nuri may SUGGEST
 * a gentler tier, framed as fit — "let's make this easier" — never as
 * decline, never as a judgment, and it never changes anything itself.
 * The caregiver decides. Signals are diary-level patterns (repeated
 * stuck/asked episodes), read as observations, not scores.
 */
object TierSlideAdvisor {
    const val STUCK_OR_ASKED_THRESHOLD: Int = 6

    fun suggestionFor(
        currentTier: NuriSettings.Tier,
        recentStuckOrAskedCount: Int,
        daysWindow: Int = 14,
    ): String? {
        if (currentTier == NuriSettings.Tier.GUIDED) return null
        if (recentStuckOrAskedCount < STUCK_OR_ASKED_THRESHOLD) return null
        val gentler = when (currentTier) {
            NuriSettings.Tier.INDEPENDENT -> "Supported"
            NuriSettings.Tier.SUPPORTED -> "Guided"
            NuriSettings.Tier.GUIDED -> return null
        }
        return "In the last $daysWindow days, help was asked for or a walk-through got stuck $recentStuckOrAskedCount times. " +
            "It may be worth trying the $gentler setting for a while — framed as fit, not decline: let's make this easier. " +
            "Only the caregiver changes this; Nuri is just noticing the pattern."
    }
}
