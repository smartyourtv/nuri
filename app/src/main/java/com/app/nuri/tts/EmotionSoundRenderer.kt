package com.app.nuri.tts

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Ported from phone Scout v2.7.63 (com.app.assistant.tts) for
 * Ilona v0.4.0 — the shaped-synthesis route for the picker voices
 * that don't carry a converted performance bank (June, the default,
 * does: see EmotionBank). Provenance + the honest ceiling are
 * recorded in files/nuri-voice-lessons-applied.md (V9/P2).
 *
 * v2.7.63: shaped-audio emotion rendering.
 *
 * Why this exists: up to v2.7.62, laughs were TTS *text* — Kokoro
 * pronouncing the words "Ha ha ha!" — which cannot sound like laughter
 * (Shawn's by-ear verdict, 2026-10-17). This renderer works on PCM the
 * selected voice itself synthesized: KokoroTtsManager generates ONE
 * short fragment ("Ha!" / "Heh!" / "Hah") through the currently selected
 * voice, and this object shapes that fragment into a laugh, chuckle, or
 * sigh. No clip library, no other person's voice — change the selected
 * voice and the laugh changes with it, because the raw material IS the
 * voice.
 *
 * How a laugh is built: the fragment's loudest pulse window is replayed
 * as a burst of pulses (~4–5/sec for a laugh, 2–3 slower ones for a
 * chuckle). Each pulse is pitch-shifted by resampling — sherpa-onnx
 * exposes no pitch parameter, so the contour is applied honestly at the
 * sample level: pulses start higher and fall across the burst, the way
 * real laughter does. Pulse gains swell then decay, timing has small
 * jitter, and shaped breath noise (the same trick BreathSoundGenerator
 * uses, seeded here) rides the onset and the exhale tail.
 *
 * Randomization is a hard rule (Shawn: emotion sounds are generated
 * fresh, no two alike): pulse count, pitch offsets, rhythm, and gains
 * are drawn per render from the caller-supplied [Random] (seeded from
 * system time at enqueue). Every random-consuming function takes the
 * Random as a parameter, so a render is deterministic for a given seed
 * and this whole file is JVM-unit-testable — no Android types anywhere.
 */
object EmotionSoundRenderer {

    enum class LaughKind { CHUCKLE, FULL_LAUGH }

    /**
     * The full shape of one laugh/chuckle render, decided up front by
     * [planLaugh] so tests can assert the assembly math exactly.
     */
    data class LaughPlan(
        val pulseCount: Int,
        val pulseIntervalMs: Int,
        val pulseLengthMs: Int,
        /** Per-pulse resample ratios (>1 = higher pitch, shorter). */
        val pitchRatios: List<Float>,
        /** Per-pulse peak gains: swell up to the peak pulse, then decay. */
        val pulseGains: List<Float>,
        val breathOnsetMs: Int,
        val breathTailMs: Int,
    )

    // ---- planning -----------------------------------------------------

    fun planLaugh(kind: LaughKind, random: Random): LaughPlan {
        val pulseCount: Int
        val pulseIntervalMs: Int
        val pulseLengthMs: Int
        val startRatio: Float
        val endRatio: Float
        val peakGain: Float
        val decay: Float
        val breathOnsetMs: Int
        val breathTailMs: Int
        when (kind) {
            LaughKind.FULL_LAUGH -> {
                pulseCount = random.nextInt(4, 7) // 4..6
                pulseIntervalMs = random.nextInt(205, 241)
                pulseLengthMs = random.nextInt(120, 161)
                startRatio = 1.16f + random.nextFloat() * 0.11f
                endRatio = 0.98f + random.nextFloat() * 0.07f
                peakGain = 0.88f + random.nextFloat() * 0.12f
                decay = 0.84f + random.nextFloat() * 0.07f
                breathOnsetMs = random.nextInt(60, 111)
                breathTailMs = random.nextInt(140, 221)
            }
            LaughKind.CHUCKLE -> {
                pulseCount = random.nextInt(2, 4) // 2..3
                pulseIntervalMs = random.nextInt(300, 361)
                pulseLengthMs = random.nextInt(120, 151)
                startRatio = 1.05f + random.nextFloat() * 0.07f
                endRatio = 0.97f + random.nextFloat() * 0.05f
                peakGain = 0.80f + random.nextFloat() * 0.12f
                decay = 0.78f + random.nextFloat() * 0.08f
                breathOnsetMs = random.nextInt(40, 81)
                breathTailMs = random.nextInt(80, 101)
            }
        }
        // Pitch contour: start high, fall across the burst, ±0.03 jitter.
        val pitchRatios = List(pulseCount) { i ->
            val t = if (pulseCount == 1) 0f else i.toFloat() / (pulseCount - 1)
            val base = startRatio + (endRatio - startRatio) * t
            (base + (random.nextFloat() - 0.5f) * 0.06f).coerceIn(0.92f, 1.34f)
        }
        // Gains: rise to the peak pulse, then decay geometrically —
        // monotonic on both sides of the peak by construction.
        val peakIdx = ((pulseCount - 1) * 0.34f).toInt()
        val pulseGains = List(pulseCount) { i ->
            if (i <= peakIdx) {
                peakGain * (0.7f + 0.3f * (i + 1).toFloat() / (peakIdx + 1))
            } else {
                peakGain * decay.pow(i - peakIdx)
            }
        }
        return LaughPlan(
            pulseCount = pulseCount,
            pulseIntervalMs = pulseIntervalMs,
            pulseLengthMs = pulseLengthMs,
            pitchRatios = pitchRatios,
            pulseGains = pulseGains,
            breathOnsetMs = breathOnsetMs,
            breathTailMs = breathTailMs,
        )
    }

    /** Exact output length of [renderLaugh] for [plan] — shared with tests. */
    fun laughLengthSamples(plan: LaughPlan, sampleRate: Int): Int {
        val onset = msToSamples(plan.breathOnsetMs, sampleRate)
        val interval = msToSamples(plan.pulseIntervalMs, sampleRate)
        val pulseLen = msToSamples(plan.pulseLengthMs, sampleRate)
        val lastPulse = (pulseLen / plan.pitchRatios.last()).toInt()
        val tail = msToSamples(plan.breathTailMs, sampleRate)
        return onset + (plan.pulseCount - 1) * interval + lastPulse + tail
    }

    // ---- laugh / chuckle ----------------------------------------------

    /**
     * Assemble a laugh (or chuckle) from one synthesized fragment of the
     * selected voice. Returns an empty array when the fragment carries no
     * usable energy — the caller then falls back to a spoken line.
     */
    fun renderLaugh(
        fragment: FloatArray,
        sampleRate: Int,
        kind: LaughKind,
        random: Random,
    ): FloatArray {
        val plan = planLaugh(kind, random)
        val src = prepareSource(fragment) ?: return FloatArray(0)
        val pulseLen = msToSamples(plan.pulseLengthMs, sampleRate)
        val window = loudestWindow(src, pulseLen)
        val total = laughLengthSamples(plan, sampleRate)
        val out = FloatArray(total)
        val onset = msToSamples(plan.breathOnsetMs, sampleRate)
        val interval = msToSamples(plan.pulseIntervalMs, sampleRate)
        val attack = msToSamples(10, sampleRate).coerceAtLeast(1)

        var firstPulseLen = 0
        var lastPulseLen = 0
        for (i in 0 until plan.pulseCount) {
            val shifted = pitchShift(window, plan.pitchRatios[i])
            if (i == 0) firstPulseLen = shifted.size
            lastPulseLen = shifted.size
            val start = onset + i * interval
            val gain = plan.pulseGains[i] * 0.9f
            for (j in shifted.indices) {
                val pos = start + j
                if (pos >= total) break
                // Pulse envelope: fast attack, decay across the pulse —
                // a burst, not a held note.
                val attackEnv = min(1f, j.toFloat() / attack)
                val decayEnv = (1f - j.toFloat() / shifted.size).pow(1.1f)
                out[pos] += shifted[j] * attackEnv * decayEnv * gain
            }
        }

        // Breath: a soft inhale-ish swell into the first pulse, and an
        // exhale tail after the last one. Low gain — seasoning, not noise.
        mixBreath(
            out, random,
            start = 0,
            length = (onset + firstPulseLen / 2).coerceAtMost(total),
            gain = 0.10f,
            rising = true,
        )
        val tailLen = (msToSamples(plan.breathTailMs, sampleRate) + lastPulseLen / 2)
            .coerceAtMost(total)
        mixBreath(
            out, random,
            start = total - tailLen,
            length = tailLen,
            gain = 0.13f,
            rising = false,
        )
        normalizePeak(out, 0.97f)
        return out
    }

    // ---- sigh -----------------------------------------------------------

    /**
     * A real exhale, not the word "Haaah": the fragment is stretched and
     * pitch-lowered, then the whole segment is dominated by shaped
     * breath under a long exhale envelope (brief rise, slow decay).
     * Total length 1.1–1.4 s — always longer than a chuckle, by design
     * of the parameter ranges.
     */
    fun renderSigh(fragment: FloatArray, sampleRate: Int, random: Random): FloatArray {
        val src = prepareSource(fragment) ?: return FloatArray(0)
        val targetMs = random.nextInt(1100, 1401)
        val total = msToSamples(targetMs, sampleRate)
        val stretch = 0.74f + random.nextFloat() * 0.12f // lower + longer
        val voicedGain = 0.50f + random.nextFloat() * 0.10f
        val breathGain = 0.15f + random.nextFloat() * 0.04f

        val out = FloatArray(total)
        val voiced = pitchShift(src, stretch)
        val vLen = min(voiced.size, total)
        for (i in 0 until vLen) out[i] += voiced[i] * voicedGain
        val noise = shapedNoise(total, random)
        for (i in 0 until total) out[i] += noise[i] * breathGain

        // Exhale envelope over everything: ~90 ms rise, exponential
        // decay, final 30 ms fade to zero so the segment can't click.
        val attack = msToSamples(90, sampleRate).coerceAtLeast(1)
        val fade = msToSamples(30, sampleRate).coerceAtLeast(1)
        val tau = (total * 0.38f).coerceAtLeast(1f)
        for (i in 0 until total) {
            val rise = min(1f, i.toFloat() / attack)
            val decay = exp(-(i.toDouble() / tau)).toFloat()
            val endFade = min(1f, (total - i).toFloat() / fade)
            out[i] *= rise * decay * endFade
        }
        normalizePeak(out, 0.9f)
        return out
    }

    // ---- speech-segment shaping (Surprise / Empathy) -------------------

    /**
     * Resample by [ratio]: >1 raises pitch (and shortens), <1 lowers it.
     * Linear interpolation — at these small ratios (±6%) artifacts are
     * inaudible for the short segments this is used on.
     */
    fun pitchShift(samples: FloatArray, ratio: Float): FloatArray {
        if (samples.isEmpty() || ratio <= 0f) return samples.copyOf()
        if (ratio == 1.0f) return samples.copyOf()
        val outLen = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = pos - idx
            val a = samples[idx]
            val b = if (idx + 1 < samples.size) samples[idx + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /**
     * Soften a spoken segment at its edges (Empathy): gain starts and
     * ends at [edgeGain], ramping smoothly to full level in between.
     * Returns a new array; the input is not mutated.
     */
    fun applyGainEnvelope(
        samples: FloatArray,
        sampleRate: Int,
        fadeInMs: Int,
        fadeOutMs: Int,
        edgeGain: Float,
    ): FloatArray {
        val out = samples.copyOf()
        val n = out.size
        if (n == 0) return out
        val fi = msToSamples(fadeInMs, sampleRate).coerceAtMost(n / 3)
        val fo = msToSamples(fadeOutMs, sampleRate).coerceAtMost(n / 3)
        for (i in 0 until n) {
            val env = when {
                i < fi -> edgeGain + (1f - edgeGain) * smooth(i.toFloat() / fi)
                i >= n - fo -> edgeGain + (1f - edgeGain) * smooth((n - 1 - i).toFloat() / fo)
                else -> 1f
            }
            out[i] *= env
        }
        return out
    }

    // ---- internals ------------------------------------------------------

    private fun msToSamples(ms: Int, sampleRate: Int): Int = (ms * sampleRate) / 1000

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Trim leading/trailing near-silence and peak-normalize a synthesized
     * fragment to 0.85. Null when the fragment has no usable energy.
     */
    private fun prepareSource(fragment: FloatArray): FloatArray? {
        if (fragment.isEmpty()) return null
        var peak = 0f
        for (s in fragment) peak = maxOf(peak, abs(s))
        if (peak < 1e-4f) return null
        val threshold = peak * 0.03f
        var first = 0
        while (first < fragment.size && abs(fragment[first]) <= threshold) first++
        var last = fragment.size - 1
        while (last > first && abs(fragment[last]) <= threshold) last--
        if (last < first) return null
        val scale = 0.85f / peak
        val out = FloatArray(last - first + 1)
        for (i in out.indices) out[i] = fragment[first + i] * scale
        return out
    }

    /** The highest-energy window of [len] samples (the vowel core). */
    private fun loudestWindow(src: FloatArray, len: Int): FloatArray {
        if (src.size <= len) return src.copyOf(len) // zero-pads if shorter
        var bestStart = 0
        var bestEnergy = -1.0
        var start = 0
        val step = (len / 4).coerceAtLeast(1)
        while (start + len <= src.size) {
            var energy = 0.0
            for (i in start until start + len) energy += src[i].toDouble() * src[i]
            if (energy > bestEnergy) {
                bestEnergy = energy
                bestStart = start
            }
            start += step
        }
        return src.copyOfRange(bestStart, bestStart + len)
    }

    /**
     * Low-passed white noise, peak-normalized — the same family of shaped
     * breath BreathSoundGenerator produces, but seeded and in float so
     * renders stay reproducible per seed.
     */
    private fun shapedNoise(length: Int, random: Random): FloatArray {
        val out = FloatArray(length)
        var last = 0f
        val alpha = 0.09f + random.nextFloat() * 0.04f
        for (i in 0 until length) {
            val white = random.nextFloat() * 2f - 1f
            last += alpha * (white - last)
            out[i] = last * 3f
        }
        var peak = 0f
        for (s in out) peak = maxOf(peak, abs(s))
        if (peak > 1e-6f) {
            val scale = 1f / peak
            for (i in out.indices) out[i] *= scale
        }
        return out
    }

    private fun mixBreath(
        out: FloatArray,
        random: Random,
        start: Int,
        length: Int,
        gain: Float,
        rising: Boolean,
    ) {
        if (length <= 0 || start >= out.size) return
        val noise = shapedNoise(length, random)
        for (i in 0 until length) {
            val pos = start + i
            if (pos >= out.size) break
            val t = i.toFloat() / length
            val env = if (rising) t else 1f - t
            out[pos] += noise[i] * gain * env
        }
    }

    private fun normalizePeak(out: FloatArray, ceiling: Float) {
        var peak = 0f
        for (s in out) peak = maxOf(peak, abs(s))
        if (peak > ceiling) {
            val scale = ceiling / peak
            for (i in out.indices) out[i] *= scale
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
    }
}
