package com.app.nuri.speech

/**
 * MicStreamHealth — the silent-microphone watchdog for the
 * always-listen service (v0.5.2).
 *
 * The defect (owner's SM-S947U, Android 17, seen on v0.4.1 and
 * v0.5.1): the AudioRecord opens and reads succeed, but the
 * samples are sustained EXACT ZEROS — the diagnostics card
 * showed sound moments 0, wake hits 0, "microphone frames are
 * all silence", across Samsung mic modes. The old loop created
 * the recorder once, logged the trouble once, and read the
 * dead stream forever.
 *
 * ROOT CAUSE, PROVEN 2026-10-20 by ADB dumpsys on the owner's
 * phone: at 14:37:18 Ilona was recording (VOICE_RECOGNITION,
 * "not silenced"); at 14:37:52 the Scout phone app entered
 * MODE_IN_COMMUNICATION and began VOICE_COMMUNICATION
 * captures; at 14:37:53 Ilona's session flipped to
 * "silenced" — Android's concurrent-capture policy mutes the
 * non-call-mode recording to exact zeros. Scout released the
 * mode at 14:41:35. So a dead stream here is usually POLICY
 * SILENCING by another app's call-mode capture — not a broken
 * microphone. The caller therefore also feeds the platform's
 * silenced flag (AudioRecordingConfiguration.isClientSilenced)
 * per window: it classifies the episode, picks the plain-
 * language diagnosis, and gates success — a step counts as
 * fixed only by frames that are live AND not silenced.
 *
 * This machine decides WHEN to repair and WHICH step to try;
 * the service performs the actual recorder work. Pure Kotlin,
 * fed per window (rms, sample count, how long the read took,
 * wall time) so the whole ladder is unit-testable.
 *
 * Rules (the self-repair law: conservative, verified, logged —
 * logging is the service's job, via CrashReports.recordRepair):
 *  - A window is DEAD when its RMS is at or below
 *    [DEAD_RMS_THRESHOLD] — about 3 LSB of 16-bit audio. A
 *    working microphone in ANY room, however quiet, produces
 *    more self-noise than that (the energy gate's live-room
 *    floor never settles below 0.001); only a digitally dead
 *    stream qualifies. A quiet room can never trigger a repair.
 *  - Dead for [DEAD_DURATION_MS] continuously → walk the
 *    ladder in order: recreate the recorder as-is, then
 *    VOICE_COMMUNICATION, then the plain MIC source, then
 *    prefer the built-in mic over an external route.
 *    VOICE_COMMUNICATION goes early on evidence (2026-10-20):
 *    the Scout phone app hears fine on the owner's SM-S947U
 *    (Android 17) and builds its AudioRecords with
 *    VOICE_COMMUNICATION, while this app's VOICE_RECOGNITION
 *    stream arrives zeroed on the same phone. Every step is
 *    VERIFIED: it counts as fixed only when
 *    [VERIFY_LIVE_WINDOWS] live windows arrive inside the
 *    verify window. Failure advances the ladder; exhausting it
 *    ends the cycle, and a fresh cycle is allowed at most once
 *    per [CYCLE_COOLDOWN_MS] while the stream stays dead.
 *  - Any live window while monitoring resets the dead clock
 *    AND the ladder — the stream is healthy, or the world
 *    changed (a headset unplugged) and the next episode starts
 *    from the cheap step again.
 *  - A dead window whose read() returned far faster than real
 *    time marks spin suspicion: the caller sleeps briefly so a
 *    dead stream cannot peg a CPU core.
 *  - SILENCED episodes (the platform flag set on any dead
 *    window of the episode) walk the SAME ladder —
 *    VOICE_COMMUNICATION is the right counter-move, the same
 *    capture class as the holder — and the same post-cooldown
 *    fresh cycles keep retrying the preferred
 *    VOICE_RECOGNITION path: when the holder's session ends
 *    (observed ~4 minutes) normal capture unsilences on its
 *    own. Every directive carries the episode's silenced
 *    classification so the service logs silenced-vs-dead
 *    distinctly and the health card can say which it is.
 */
class MicStreamHealth(
    private val deadRmsThreshold: Float = DEAD_RMS_THRESHOLD,
    private val deadDurationMs: Long = DEAD_DURATION_MS,
    private val verifyDurationMs: Long = VERIFY_DURATION_MS,
    private val verifyLiveWindows: Int = VERIFY_LIVE_WINDOWS,
    private val cycleCooldownMs: Long = CYCLE_COOLDOWN_MS
) {

    enum class RepairStep { RECREATE_SAME, SOURCE_VOICE_COMMUNICATION, SOURCE_MIC, PREFER_BUILTIN_MIC }

    sealed class Action {
        object None : Action()
        data class AttemptRepair(val step: RepairStep) : Action()
        data class RepairConfirmed(val step: RepairStep) : Action()
        object RepairExhausted : Action()
    }

    data class Directive(
        val action: Action = Action.None,
        val spinSuspected: Boolean = false,
        /**
         * The current dead episode's classification: true when
         * the platform reported this recording silenced by
         * another app's call-mode capture on any dead window of
         * the episode. Carried on every directive so the
         * service can log and word silenced-vs-dead distinctly.
         */
        val silenced: Boolean = false
    )

    private enum class Phase { MONITORING, VERIFYING, EXHAUSTED }

    private var phase = Phase.MONITORING
    private var deadSinceMs = -1L
    private var ladderIndex = 0
    private var currentStep: RepairStep? = null
    private var verifyStartMs = 0L
    private var verifyLiveCount = 0
    private var cooldownUntilMs = 0L
    private var episodeSilenced = false

    /** The step a repair is currently verifying, if any (diagnostics/tests). */
    val verifyingStep: RepairStep? get() = if (phase == Phase.VERIFYING) currentStep else null

    /** True once the ladder has been exhausted and no repair is in flight. */
    val isExhausted: Boolean get() = phase == Phase.EXHAUSTED

    /** The current dead episode's classification (see [Directive.silenced]). */
    val currentEpisodeSilenced: Boolean get() = episodeSilenced

    fun onWindow(rms: Float, samples: Int, readElapsedMs: Long, nowMs: Long, clientSilenced: Boolean = false): Directive {
        val live = rms > deadRmsThreshold
        val spin = !live && isSpinRead(samples, readElapsedMs)
        return when (phase) {
            Phase.MONITORING -> {
                if (live) {
                    deadSinceMs = -1L
                    episodeSilenced = false
                    Directive()
                } else {
                    if (deadSinceMs < 0L) deadSinceMs = nowMs
                    if (clientSilenced) episodeSilenced = true
                    if (nowMs - deadSinceMs >= deadDurationMs && nowMs >= cooldownUntilMs) {
                        beginStep(LADDER[ladderIndex], nowMs)
                        Directive(Action.AttemptRepair(LADDER[ladderIndex]), spin, silenced = episodeSilenced)
                    } else {
                        Directive(Action.None, spin, silenced = episodeSilenced)
                    }
                }
            }
            Phase.VERIFYING -> {
                // Success is frames that are live AND not silenced.
                if (live && !clientSilenced) {
                    verifyLiveCount++
                    if (verifyLiveCount >= verifyLiveWindows) {
                        val step = currentStep ?: LADDER[0]
                        val wasSilenced = episodeSilenced
                        phase = Phase.MONITORING
                        ladderIndex = 0
                        currentStep = null
                        deadSinceMs = -1L
                        episodeSilenced = false
                        Directive(Action.RepairConfirmed(step), silenced = wasSilenced)
                    } else {
                        Directive(silenced = episodeSilenced)
                    }
                } else if (nowMs - verifyStartMs >= verifyDurationMs) {
                    if (!live && clientSilenced) episodeSilenced = true
                    advanceAfterFailedStep(nowMs, spin)
                } else {
                    if (!live && clientSilenced) episodeSilenced = true
                    Directive(Action.None, spin, silenced = episodeSilenced)
                }
            }
            Phase.EXHAUSTED -> {
                if (live) {
                    phase = Phase.MONITORING
                    ladderIndex = 0
                    deadSinceMs = -1L
                    cooldownUntilMs = 0L
                    episodeSilenced = false
                    Directive()
                } else if (nowMs >= cooldownUntilMs) {
                    // Cooldown over and the stream is still dead:
                    // one fresh cycle, starting from the cheap step.
                    // The silenced latch carries over — it is the
                    // same episode until sound returns.
                    if (clientSilenced) episodeSilenced = true
                    phase = Phase.MONITORING
                    ladderIndex = 0
                    deadSinceMs = nowMs - deadDurationMs
                    Directive(Action.None, spin, silenced = episodeSilenced)
                } else {
                    if (clientSilenced) episodeSilenced = true
                    Directive(Action.None, spin, silenced = episodeSilenced)
                }
            }
        }
    }

    /**
     * The service could not even create a recorder for the step
     * it was told to try: that step has failed by definition.
     * Advances the ladder exactly as a failed verification does.
     */
    fun onRepairFailedToStart(nowMs: Long): Directive {
        if (phase != Phase.VERIFYING) return Directive()
        return advanceAfterFailedStep(nowMs, spin = false)
    }

    private fun beginStep(step: RepairStep, nowMs: Long) {
        currentStep = step
        phase = Phase.VERIFYING
        verifyStartMs = nowMs
        verifyLiveCount = 0
    }

    private fun advanceAfterFailedStep(nowMs: Long, spin: Boolean): Directive {
        ladderIndex++
        if (ladderIndex >= LADDER.size) {
            phase = Phase.EXHAUSTED
            currentStep = null
            cooldownUntilMs = nowMs + cycleCooldownMs
            return Directive(Action.RepairExhausted, spin, silenced = episodeSilenced)
        }
        val step = LADDER[ladderIndex]
        beginStep(step, nowMs)
        return Directive(Action.AttemptRepair(step), spin, silenced = episodeSilenced)
    }

    private fun isSpinRead(samples: Int, readElapsedMs: Long): Boolean {
        if (samples <= 0 || readElapsedMs < 0L) return false
        val realTimeMs = samples * 1000L / SAMPLE_RATE_HZ
        return readElapsedMs * SPIN_FACTOR < realTimeMs
    }

    companion object {
        /** ≈3 LSB RMS of 16-bit audio — below any real microphone's self-noise. */
        const val DEAD_RMS_THRESHOLD = 0.0001f
        const val DEAD_DURATION_MS = 2_500L
        const val VERIFY_DURATION_MS = 3_000L
        const val VERIFY_LIVE_WINDOWS = 4
        const val CYCLE_COOLDOWN_MS = 30_000L
        const val SAMPLE_RATE_HZ = 16_000L
        /** A read returning SPIN_FACTOR× faster than real time is not real audio. */
        const val SPIN_FACTOR = 8L

        val LADDER: List<RepairStep> = listOf(
            RepairStep.RECREATE_SAME,
            RepairStep.SOURCE_VOICE_COMMUNICATION,
            RepairStep.SOURCE_MIC,
            RepairStep.PREFER_BUILTIN_MIC
        )

        /**
         * The health card's microphone line for a stream that
         * isn't hearing the room, in senior-readable words —
         * the silenced case names the actual cause (another
         * app's call-mode capture) instead of blaming the mic.
         */
        fun silenceMessage(silenced: Boolean): String = if (silenced) {
            "Microphone: another app is using the microphone in call mode right now — Ilona will hear you again when it finishes."
        } else {
            "Microphone: seems silent. Check nothing is covering it, or try speaking closer."
        }
    }
}
