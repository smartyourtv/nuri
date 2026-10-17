package com.app.nuri.speech

/**
 * Voice pipeline bones — contracts, extended in Phase D with the offline
 * safety seam. The listening/speaking engines themselves remain gated on
 * on-device work (a "hey nuri" wake model + TTS engine); the safety intent
 * layer below is deliberately LOCAL so it can be wired without any network.
 */
enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

interface WakeWordDetector {
    fun isRunning(): Boolean
}

/** Offline safety seam: parse + handle locally, never via the network. */
class OfflineSafetyPipeline {
    fun onSpokenText(spoken: String, emergencyContactName: String = "your caregiver"): SafetyAction =
        OfflineSafetyHandlers.handle(SafetyIntentParser.parse(spoken), emergencyContactName)
    fun diagnosticsText(): String = OfflineSafetyHandlers.diagnosticsText()
}
