package com.app.nuri.tts

import com.app.nuri.config.NuriSettings
import com.app.nuri.tier.TierDefaults

/**
 * TTS pipeline — contract extended in Phase D (engine itself still gated:
 * the sherpa-onnx / Kokoro wiring lands with on-device voice work; nothing
 * speaks until then, and no method here pretends otherwise).
 *
 * Voice continuity (brief SS3): ONE voice id, from settings, forever.
 * Emotion-aware requests carry prosody HINTS derived from the emotion level;
 * Guided requests are clamped to the comforting range before hints are made.
 * Laughs, when the engine lands, must be rendered BY that one voice (Scout
 * EmotionEngine lesson), never from a clip in someone else's voice.
 */
interface TtsEngine {
    fun currentVoice(): String?
}

data class ProsodyHints(val rateMultiplier: Float, val warmth: Float, val expressiveness: Float, val allowSurprise: Boolean, val allowLaughter: Boolean)
data class SpeakRequest(val text: String, val voiceId: String, val emotionLevel: NuriSettings.EmotionLevel, val prosody: ProsodyHints, val slowerSpeech: Boolean)

object EmotionProsody {
    fun hintsFor(level: NuriSettings.EmotionLevel, tier: NuriSettings.Tier): ProsodyHints {
        val effective = if (tier == NuriSettings.Tier.GUIDED) TierDefaults.clampEmotionForGuided(level) else level
        return when (effective) {
            NuriSettings.EmotionLevel.CALM -> ProsodyHints(0.95f, 0.1f, 0.1f, allowSurprise = false, allowLaughter = false)
            NuriSettings.EmotionLevel.GENTLE -> ProsodyHints(0.92f, 0.4f, 0.3f, allowSurprise = false, allowLaughter = false)
            NuriSettings.EmotionLevel.WARM -> ProsodyHints(0.95f, 0.6f, 0.5f, allowSurprise = true, allowLaughter = true)
            NuriSettings.EmotionLevel.EXPRESSIVE -> ProsodyHints(1.0f, 0.8f, 0.85f, allowSurprise = true, allowLaughter = true)
            NuriSettings.EmotionLevel.FULL_HEARTED -> ProsodyHints(1.0f, 1.0f, 1.0f, allowSurprise = true, allowLaughter = true)
        }
    }
    /**
     * Scaffold contract — currently UNREFERENCED (v0.5.2 audit,
     * caller-traced): the live speech path (IlonaSpeaker) uses
     * [hintsFor] directly. WARNING for any future caller: this
     * bakes the slowerSpeech 0.92 into the request's rate, so the
     * baked rate must NOT be passed on to VoiceProfiles.speedFor
     * with slowerSpeech=true — that would apply the slowdown
     * twice (~0.85). Either consume the baked rate as-is, or use
     * hintsFor + speedFor like the live path does.
     */
    fun buildRequest(text: String, voiceId: String, level: NuriSettings.EmotionLevel, tier: NuriSettings.Tier, slowerSpeech: Boolean): SpeakRequest {
        val hints = hintsFor(level, tier)
        // Bug-hunt fix (NU-1): store the EFFECTIVE (Guided-clamped) level in the
        // request itself, not the raw stored level — otherwise a future engine
        // keying off request.emotionLevel would bypass the Guided clamp even
        // though the prosody hints were clamped.
        val effective = if (tier == NuriSettings.Tier.GUIDED) TierDefaults.clampEmotionForGuided(level) else level
        val rate = if (slowerSpeech) hints.rateMultiplier * 0.92f else hints.rateMultiplier
        return SpeakRequest(text, voiceId, effective, hints.copy(rateMultiplier = rate), slowerSpeech)
    }
    fun descriptionFor(level: NuriSettings.EmotionLevel): String = when (level) {
        NuriSettings.EmotionLevel.CALM -> "Mostly neutral and steady, with very little emotion."
        NuriSettings.EmotionLevel.GENTLE -> "Soft warmth, with gentle reactions now and then."
        NuriSettings.EmotionLevel.WARM -> "Natural warmth. Ilona chuckles when something is funny and sounds caring when you are down. This is the usual setting."
        NuriSettings.EmotionLevel.EXPRESSIVE -> "Lots of feeling: laughs, surprise, and comfort, all in Ilona's same voice."
        NuriSettings.EmotionLevel.FULL_HEARTED -> "The warmest and most lively setting. Ilona laughs freely and sounds fully engaged."
    }
}

/**
 * Retired scaffold engine (v0.4.0): the real engine is
 * tts.android.KokoroVoiceEngine behind the IlonaSpeaker seam, with
 * the platform voice as the honestly-labeled fallback. This class
 * remains only so older references resolve; it is not used.
 */
class NotBuiltTtsEngine : TtsEngine {
    override fun currentVoice(): String? = null
    fun statusText(): String = "Speaking voice: Ilona's own voice (Kokoro) when downloaded — see the voice health card for the live state."
}
