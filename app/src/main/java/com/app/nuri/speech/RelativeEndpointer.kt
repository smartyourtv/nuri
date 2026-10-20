package com.app.nuri.speech

import kotlin.math.max

/**
 * RelativeEndpointer — decides when an utterance is finished, from
 * the utterance's OWN energy, never from a fixed loudness line or a
 * fixed long cap (lessons V4 + LL2 + LL8 in
 * files/nuri-voice-lessons-applied.md).
 *
 * The laptop failure this replaces: a fixed capture window burned
 * its whole cap in a noisy room, and a fixed threshold clipped
 * quiet speakers. Here:
 *  - ONSET: a frame above [onsetRms] starts the utterance.
 *  - CLOSE: the utterance ends when the level stays below
 *    max(peak * [closeRatio], [floorAfterOnset]) for [pauseMs].
 *    The threshold RELATIVE to this utterance's peak is what lets
 *    a soft voice end cleanly in a loud room and a loud voice end
 *    cleanly in a quiet one.
 *  - SHORT-ANSWER PROTECTION (LL8): once onset has happened, the
 *    absolute floor drops to [floorAfterOnset] — very low — and
 *    only [minSpeechMs] of actual speech is required, so a
 *    whispered "yes" is a complete utterance, never clipped for
 *    being short or quiet.
 *  - HANGOVER (V1): closing is disabled for [hangoverMs] after
 *    [start] — the wake phrase and the first word are never cut.
 *  - SAFETY MAX: [safetyMaxMs] bounds a stuck capture; hitting it
 *    is reported in the result so the caller can log it (LL9
 *    honesty — a cap hit is a fact, never silent).
 *
 * Pure Kotlin: feed (rms, atMs) frames; unit-tested with synthetic
 * envelopes.
 */
class RelativeEndpointer(
    private val onsetRms: Float = 0.02f,
    private val closeRatio: Float = 0.25f,
    private val floorAfterOnset: Float = 0.004f,
    private val pauseMs: Long = 900L,
    private val minSpeechMs: Long = 250L,
    private val hangoverMs: Long = ListenSessionMachine.WAKE_HANGOVER_MS,
    private val safetyMaxMs: Long = 20_000L
) {
    enum class CloseReason { PAUSE, SAFETY_MAX }

    data class Result(val reason: CloseReason, val speechMs: Long, val peakRms: Float)

    private var startedAtMs = -1L
    private var onsetAtMs = -1L
    private var lastSpeechAtMs = -1L
    private var speechMs = 0L
    private var lastFrameAtMs = -1L
    private var peakRms = 0f

    val hasOnset: Boolean get() = onsetAtMs >= 0

    /** Begin watching a fresh capture at [nowMs] (the wake moment). */
    fun start(nowMs: Long) {
        startedAtMs = nowMs
        onsetAtMs = -1L
        lastSpeechAtMs = -1L
        speechMs = 0L
        lastFrameAtMs = -1L
        peakRms = 0f
    }

    /**
     * Feed one frame. Returns a [Result] exactly once, when the
     * utterance should close; otherwise null.
     */
    fun onFrame(rms: Float, atMs: Long): Result? {
        if (startedAtMs < 0) start(atMs)
        if (rms > peakRms) peakRms = rms
        val frameMs = if (lastFrameAtMs >= 0) atMs - lastFrameAtMs else 0L
        lastFrameAtMs = atMs

        val closeThreshold = max(peakRms * closeRatio, floorAfterOnset)
        val isSpeech = if (!hasOnset) rms >= onsetRms else rms >= closeThreshold

        if (isSpeech) {
            if (!hasOnset) onsetAtMs = atMs
            lastSpeechAtMs = atMs
            speechMs += frameMs.coerceIn(0L, 200L)
        }

        if (!hasOnset) {
            // No speech yet: only the safety max can close (an empty capture).
            return if (atMs - startedAtMs >= safetyMaxMs) Result(CloseReason.SAFETY_MAX, 0L, peakRms) else null
        }

        val inHangover = atMs - startedAtMs < hangoverMs
        if (!inHangover) {
            if (speechMs >= minSpeechMs && atMs - lastSpeechAtMs >= pauseMs) {
                return Result(CloseReason.PAUSE, speechMs, peakRms)
            }
            if (atMs - startedAtMs >= safetyMaxMs) {
                return Result(CloseReason.SAFETY_MAX, speechMs, peakRms)
            }
        }
        return null
    }
}
