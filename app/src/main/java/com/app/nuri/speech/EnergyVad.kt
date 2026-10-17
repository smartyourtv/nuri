package com.app.nuri.speech

import kotlin.math.sqrt

/**
 * Energy VAD gate for "Hey Nuri" Always listen.
 *
 * Provenance: re-implemented in the Nuri package from the pattern proven in
 * Scout Build 1 (speech/EnergyVad, 80 ms RMS windows, adaptive noise floor,
 * hangover tail) — NOT a verbatim copy of Scout source. Interim lesson folded
 * in from Build 1: a cheap pre-gate in front of the wake-word spotter is what
 * makes always-listening affordable on battery; the spotter must not run
 * inference on silence.
 *
 * Pure Kotlin, unit-testable. Window = nominally 80 ms (1280 samples @16kHz).
 */
class EnergyVad(
    private val speechRatio: Float = 3.0f,
    private val absoluteMinRms: Float = 0.015f,
    private val hangoverWindows: Int = 4,
    initialNoiseFloor: Float = 0.005f
) {
    var noiseFloor: Float = initialNoiseFloor; private set
    var lastRms: Float = 0f; private set
    var speechWindows: Long = 0; private set
    var totalWindows: Long = 0; private set
    private var hangoverRemaining = 0

    fun rmsOf(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) { val d = s.toDouble(); sum += d * d }
        return sqrt(sum / samples.size).toFloat()
    }
    fun rmsOf(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) { val d = s / 32768.0; sum += d * d }
        return sqrt(sum / samples.size).toFloat()
    }
    /** Returns true when the gate is open (speech, or hangover tail). */
    fun acceptWindow(samples: FloatArray): Boolean = acceptRms(rmsOf(samples))
    fun acceptWindow(samples: ShortArray): Boolean = acceptRms(rmsOf(samples))
    private fun acceptRms(rms: Float): Boolean {
        lastRms = rms; totalWindows++
        val threshold = maxOf(noiseFloor * speechRatio, absoluteMinRms)
        if (rms > threshold) { speechWindows++; hangoverRemaining = hangoverWindows; return true }
        noiseFloor = (noiseFloor * 0.95f + rms * 0.05f).coerceAtLeast(0.001f)
        if (hangoverRemaining > 0) { hangoverRemaining--; return true }
        return false
    }
    fun reset() { hangoverRemaining = 0; speechWindows = 0; totalWindows = 0 }
    fun diagnosticsText(): String =
        "Energy gate: ${speechWindows} speech windows of ${totalWindows} heard. " +
        "Last sound level ${"%.3f".format(lastRms)}, quiet-room level ${"%.3f".format(noiseFloor)}."
}
