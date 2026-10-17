package com.app.nuri.tier

import com.app.nuri.config.NuriSettings

/**
 * Tier logic — pure Kotlin, unit-testable, no Android types.
 *
 * Law (brief SS4): tiers set defaults; per-person toggles override them.
 * Guided clamps are HARD: games OFF, testing OFF (no drills/quizzes/prompts
 * that test memory), emotion clamped to the comforting range. A toggle can
 * never override a Guided clamp — that is Nuri integrity, not a preference.
 * Scaffolding-not-treatment: nothing here diagnoses or suggests a tier as a
 * clinical judgement; tier changes are a family/caregiver choice.
 */
data class PersonToggles(
    val gamesAllowed: Boolean? = null,
    val emotionLevel: NuriSettings.EmotionLevel? = null,
    val slowerSpeech: Boolean? = null,
    val longerListenWindow: Boolean? = null,
    val alwaysListenEnabled: Boolean? = null
)

data class EffectiveSettings(
    val tier: NuriSettings.Tier,
    val gamesAllowed: Boolean,
    val testingAllowed: Boolean,
    val emotionLevel: NuriSettings.EmotionLevel,
    val slowerSpeech: Boolean,
    val longerListenWindow: Boolean,
    val speechRateMultiplier: Float,
    val listenWindowMultiplier: Float,
    val alwaysListenEnabled: Boolean,
    val multiStepTasksAllowed: Boolean,
    val openEndedChoicesAllowed: Boolean
)

object TierDefaults {
    fun forTier(tier: NuriSettings.Tier): EffectiveSettings = when (tier) {
        NuriSettings.Tier.INDEPENDENT -> EffectiveSettings(
            tier, gamesAllowed = true, testingAllowed = false,
            emotionLevel = NuriSettings.EmotionLevel.WARM,
            slowerSpeech = false, longerListenWindow = false,
            speechRateMultiplier = 1.0f, listenWindowMultiplier = 1.0f,
            alwaysListenEnabled = true, multiStepTasksAllowed = true,
            openEndedChoicesAllowed = true
        )
        NuriSettings.Tier.SUPPORTED -> EffectiveSettings(
            tier, gamesAllowed = true, testingAllowed = false,
            emotionLevel = NuriSettings.EmotionLevel.WARM,
            slowerSpeech = true, longerListenWindow = true,
            speechRateMultiplier = 0.9f, listenWindowMultiplier = 1.5f,
            alwaysListenEnabled = true, multiStepTasksAllowed = false,
            openEndedChoicesAllowed = false
        )
        NuriSettings.Tier.GUIDED -> EffectiveSettings(
            tier, gamesAllowed = false, testingAllowed = false,
            emotionLevel = NuriSettings.EmotionLevel.GENTLE,
            slowerSpeech = true, longerListenWindow = true,
            speechRateMultiplier = 0.85f, listenWindowMultiplier = 1.5f,
            alwaysListenEnabled = true, multiStepTasksAllowed = false,
            openEndedChoicesAllowed = false
        )
    }
    // testingAllowed is false in EVERY tier as a product feature: Guided bans
    // it by law, and Independent/Supported use guided recall (yes/no, choice)
    // framed as conversation, never scored testing. The field exists so a
    // future proposal must explicitly confront this default.
    /** Comforting range for Guided: Calm..Gentle only (brief SS8: top levels soften, no startling surprises). */
    val GUIDED_EMOTION_MAX: NuriSettings.EmotionLevel = NuriSettings.EmotionLevel.GENTLE
    fun clampEmotionForGuided(level: NuriSettings.EmotionLevel): NuriSettings.EmotionLevel =
        if (level.ordinal <= GUIDED_EMOTION_MAX.ordinal) level else GUIDED_EMOTION_MAX
}

object EffectiveSettingsResolver {
    fun resolve(tier: NuriSettings.Tier, toggles: PersonToggles): EffectiveSettings {
        val base = TierDefaults.forTier(tier)
        var eff = base.copy(
            gamesAllowed = toggles.gamesAllowed ?: base.gamesAllowed,
            emotionLevel = toggles.emotionLevel ?: base.emotionLevel,
            slowerSpeech = toggles.slowerSpeech ?: base.slowerSpeech,
            longerListenWindow = toggles.longerListenWindow ?: base.longerListenWindow,
            alwaysListenEnabled = toggles.alwaysListenEnabled ?: base.alwaysListenEnabled
        )
        if (eff.slowerSpeech) eff = eff.copy(speechRateMultiplier = minOf(eff.speechRateMultiplier, 0.9f))
        if (eff.longerListenWindow) eff = eff.copy(listenWindowMultiplier = maxOf(eff.listenWindowMultiplier, 1.5f))
        if (tier == NuriSettings.Tier.GUIDED) {
            eff = eff.copy(
                gamesAllowed = false, testingAllowed = false,
                emotionLevel = TierDefaults.clampEmotionForGuided(eff.emotionLevel),
                multiStepTasksAllowed = false, openEndedChoicesAllowed = false
            )
        }
        return eff
    }
}
