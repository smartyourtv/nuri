package com.app.nuri.tts

import kotlin.math.*
import kotlin.random.Random

/**
 * Ported from phone Scout (com.app.assistant.tts) for Ilona v0.4.0 —
 * shaped breaths for the non-bank voices (files/
 * nuri-voice-lessons-applied.md, P3). June's breaths are bank
 * performances; these serve the other picker voices.
 *
 * Generates subtle breath sounds for insertion at natural pauses in TTS.
 *
 * Breath sounds are one of the strongest "human" cues in speech.
 * This generates a soft, filtered noise burst that sounds like a quiet inhale.
 *
 * The breath is:
 * - Very quiet (-30dB relative to speech)
 * - Short (150-250ms)
 * - Low-pass filtered (breaths are mostly low-frequency)
 * - With natural attack/decay envelope
 */
object BreathSoundGenerator {

    /**
     * Generate a breath sound as 16-bit PCM samples.
     *
     * @param sampleRate Audio sample rate (e.g., 24000 for Kokoro)
     * @param durationMs Breath duration in milliseconds (150-300 recommended;
     *   vary per breath — identical durations sound robotic)
     * @param volumeScale Relative loudness (1.0 = default; vary 0.7-1.3 per
     *   breath so no two breaths sound identical)
     * @return ShortArray of PCM samples
     */
    fun generateBreath(
        sampleRate: Int,
        durationMs: Int = 200,
        volumeScale: Float = 1.0f,
    ): ShortArray {
        val numSamples = (sampleRate * durationMs / 1000)
        val samples = ShortArray(numSamples)

        // Vary attack/decay slightly per breath for natural variation
        val attackMs = 40.0 + Random.nextFloat() * 30.0 // 40-70ms
        val decayMs = 80.0 + Random.nextFloat() * 60.0 // 80-140ms

        var lastOutput = 0.0
        // Slightly vary the filter per breath (0.08-0.13) so the tone differs
        val alpha = 0.08 + Random.nextFloat() * 0.05

        for (i in 0 until numSamples) {
            val tMs = i * 1000.0 / sampleRate

            // Envelope: attack then decay
            val envelope = when {
                tMs < attackMs -> tMs / attackMs
                tMs < durationMs - decayMs -> 1.0
                else -> max(0.0, (durationMs - tMs) / decayMs)
            }

            // Apply smooth curve to envelope (avoid clicks)
            val smoothEnvelope = sin(envelope * PI / 2).pow(2)

            // Generate filtered noise (pink-ish via low-pass)
            val whiteNoise = Random.nextFloat() * 2 - 1
            lastOutput = lastOutput + alpha * (whiteNoise - lastOutput)
            val filteredNoise = lastOutput * 3.0 // Compensate for filter attenuation

            // Scale to very quiet (-30dB ≈ 0.032 amplitude), with per-breath volume variation
            // Max 16-bit is 32767, so 32767 * 0.032 ≈ 1000
            val amplitude = 1000.0 * smoothEnvelope * volumeScale
            samples[i] = (filteredNoise * amplitude).toInt().toShort().coerceIn(-32768, 32767).toShort()
        }

        return samples
    }

    /**
     * Generate silence (for pauses without breath).
     */
    fun generateSilence(sampleRate: Int, durationMs: Int): ShortArray {
        val numSamples = (sampleRate * durationMs / 1000)
        return ShortArray(numSamples) { 0 }
    }
}
