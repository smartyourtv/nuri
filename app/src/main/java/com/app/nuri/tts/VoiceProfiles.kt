package com.app.nuri.tts

/**
 * The curated Kokoro voices Ilona offers (v0.4.0). Speaker ids are the
 * sherpa-onnx kokoro-multi-lang-v1_0 ids — the same curated list the
 * phone Scout ships (SettingsRepository.KOKORO_VOICES), so a voice a
 * family already knows from Scout sounds the same here (standing
 * rule: one engine, per-product profiles).
 *
 * JUNE is the default (Shawn, 2026-10-19: "I want Ilona to use the
 * June voice", clarified "like June's ON THE LAPTOP" — June's
 * identity at laptop production quality). On the phone, June is the
 * Duo alternate voice: with Scout's own voice am_adam selected, June
 * speaks with af_heart (sid 3) — pinned here directly so Ilona's
 * June never depends on another app's settings.
 *
 * Voice continuity (V17): the stored value is a speaker id in the
 * existing single_voice_id settings key. Updates never write it.
 * June applies only when nothing — or the retired scaffold sentinel
 * — is stored.
 */
object VoiceProfiles {

    data class Voice(
        val sid: Int,
        val kokoroId: String,
        val displayName: String,
        val blurb: String,
        /** A line this voice says about itself — the picker's tap-to-hear sample. */
        val sampleLine: String,
        /**
         * The emotion-bank asset dir serving this voice, or null when
         * the voice has no bank (its emotion sounds are shaped).
         * Emotion sounds follow the selected voice (owner decision,
         * 2026-10-19): June → her own bank; the men's voices → the
         * male bank; Bella and Sarah → shaped route for now.
         */
        val bankDir: String? = null
    )

    val JUNE = Voice(
        sid = 3,
        kokoroId = "af_heart",
        displayName = "June — Ilona's voice",
        blurb = "Warm and friendly. This is the voice Ilona starts with.",
        sampleLine = "Hello, I'm June. I'll be helping you here. We can take everything one step at a time.",
        bankDir = EmotionBank.ASSET_DIR
    )

    val VOICES: List<Voice> = listOf(
        JUNE,
        Voice(2, "af_bella", "Bella", "A clear, gentle woman's voice.",
            "Hello, I'm Bella. It's nice to meet you. I'm here whenever you need a hand."),
        Voice(9, "af_sarah", "Sarah", "A calm, steady woman's voice.",
            "Hello, I'm Sarah. There's no hurry at all. We'll go at your pace."),
        Voice(11, "am_adam", "Adam", "A warm, friendly man's voice.",
            "Hello, I'm Adam. Happy to help. Just tell me what you need.",
            bankDir = EmotionBank.ASSET_DIR_MALE),
        Voice(15, "am_liam", "Liam", "A clear, cheerful man's voice.",
            "Hello, I'm Liam. Good to see you. What can I do for you today?",
            bankDir = EmotionBank.ASSET_DIR_MALE),
        Voice(16, "am_michael", "Michael", "A deep, calm man's voice.",
            "Hello, I'm Michael. I'm right here. We can take it slowly.",
            bankDir = EmotionBank.ASSET_DIR_MALE),
        Voice(17, "am_onyx", "Onyx", "A rich, steady man's voice.",
            "Hello, I'm Onyx. At your service. Tell me what you'd like to do.",
            bankDir = EmotionBank.ASSET_DIR_MALE)
    )

    /** The retired scaffold default in the settings store. */
    const val LEGACY_UNSET = "nuri-voice-default"

    fun bySid(sid: Int): Voice = VOICES.firstOrNull { it.sid == sid } ?: JUNE

    /**
     * Resolve the stored setting to a voice: a stored speaker id wins
     * (June never overrides a person's choice); unset / legacy /
     * unparseable values resolve to June — the fresh-install default.
     */
    fun resolve(stored: String?): Voice {
        if (stored.isNullOrBlank() || stored == LEGACY_UNSET) return JUNE
        val sid = stored.trim().toIntOrNull() ?: return JUNE
        return bySid(sid)
    }

    /** What gets stored when a person picks [voice]. */
    fun storedValue(voice: Voice): String = voice.sid.toString()

    /**
     * Ilona's speech profile, applied on top of ANY selected
     * voice — the profile is the product's, the timbre is the
     * person's choice. Kokoro speed: 1.0 is the voice's natural
     * rate.
     *
     * Profile base = 1.0 (v0.5.2): the owner auditioned offline
     * renders of June's sample line at 0.92 / 0.95 / 1.0 on
     * 2026-10-20 and picked the natural rate ("I like C") — the
     * earlier 0.92 base read as a little unnatural on-device.
     * The slowerSpeech accessibility multiplier (0.92f) stays:
     * it is a person's explicit choice, applied exactly once,
     * here — callers pass the RAW emotion hint rate, never a
     * pre-slowed one (see the warning on EmotionProsody
     * .buildRequest).
     */
    fun speedFor(voice: Voice, emotionRateMultiplier: Float, slowerSpeech: Boolean): Float {
        val profile = 1.0f // Natural rate — owner-picked by ear, 2026-10-20.
        val slower = if (slowerSpeech) 0.92f else 1.0f
        return (profile * slower * emotionRateMultiplier).coerceIn(0.6f, 1.3f)
    }
}
