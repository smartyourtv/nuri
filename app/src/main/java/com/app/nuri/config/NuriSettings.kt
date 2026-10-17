package com.app.nuri.config

/**
 * Nuri configuration constants — scaffold.
 *
 * Mirrors the role of Scout's persona/config system (adapted in the brief to
 * tier profiles). Only defaults that the brief has already decided live
 * here; everything else is added with its gated feature. Nothing here is a
 * hard-coded behavior a user can't change — defaults stay changeable.
 */
object NuriSettings {

    /** Cognitive tiers (brief §4). Implementation of tier behavior is GATED. */
    enum class Tier { INDEPENDENT, SUPPORTED, GUIDED }

    /** First-install modes (brief §5). Setup implementation is GATED. */
    enum class FirstInstallMode { INDEPENDENT, CAREGIVER, THERAPIST }

    /** Access modes (brief §5). */
    enum class AccessMode { INDEPENDENT, HYBRID, MANAGED }

    /**
     * "How much feeling Nuri shows" (brief §8). Warm is the default.
     * Guided tier clamps to the comforting range at runtime (gated).
     */
    enum class EmotionLevel(val label: String) {
        CALM("Calm"),
        GENTLE("Gentle"),
        WARM("Warm"),
        EXPRESSIVE("Expressive"),
        FULL_HEARTED("Full-hearted")
    }

    val DEFAULT_TIER: Tier = Tier.SUPPORTED // build Supported first (brief §4)
    val DEFAULT_EMOTION_LEVEL: EmotionLevel = EmotionLevel.WARM

    /** Wake phrase. The "hey nuri" wake model itself is GATED voice work. */
    const val WAKE_PHRASE: String = "hey nuri"

    /** Bluetooth remembered-speaker slots (brief §9): Nuri 10 (Scout has 5). */
    const val BLUETOOTH_SLOT_COUNT: Int = 10
}
