package com.app.nuri.speech

import com.app.nuri.speech.MicStreamHealth.Action
import com.app.nuri.speech.MicStreamHealth.RepairStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silent-microphone ladder (v0.5.2): dead-stream detection,
 * repair ordering, verify-before-success, rate limiting. Time is
 * synthetic — 80 ms windows, timestamps at window start, so the
 * 2.5 s dead duration first crosses on the window stamped
 * start+2560 (the 33rd window of a dead run).
 */
class MicStreamHealthTest {

    private val windowMs = 80L
    private val samples = 1_280

    /** Feed [count] windows starting at [startMs]; returns the directives. */
    private fun feed(
        h: MicStreamHealth, count: Int, startMs: Long,
        rms: Float, readElapsedMs: Long = windowMs, silenced: Boolean = false
    ): List<MicStreamHealth.Directive> =
        (0 until count).map { i -> h.onWindow(rms, samples, readElapsedMs, startMs + i * windowMs, clientSilenced = silenced) }

    private fun dead(h: MicStreamHealth, count: Int, startMs: Long) = feed(h, count, startMs, rms = 0f)
    private fun live(h: MicStreamHealth, count: Int, startMs: Long) = feed(h, count, startMs, rms = 0.02f)

    /** Dead windows the platform reports as policy-silenced (amendment 2). */
    private fun deadSilenced(h: MicStreamHealth, count: Int, startMs: Long) =
        feed(h, count, startMs, rms = 0f, silenced = true)

    @Test fun liveStreamNeverTriggersRepair() {
        val h = MicStreamHealth()
        val out = live(h, 750, 0) // a full minute of live windows
        assertTrue(out.all { it.action == Action.None && !it.spinSuspected })
    }

    @Test fun quietRoomIsNotDead() {
        // A working mic in a silent room: RMS ~0.002, far above the
        // dead threshold. 45 s of it must never trigger a repair.
        val h = MicStreamHealth()
        val out = feed(h, 45_000 / 80, 0, rms = 0.002f)
        assertTrue(out.all { it.action == Action.None })
    }

    @Test fun deadStreamTriggersFirstRepairOnlyAfterTheDeadDuration() {
        val h = MicStreamHealth()
        // 32 windows: the dead span reaches 2.48 s — still just watching.
        val early = dead(h, 32, 0)
        assertTrue(early.all { it.action == Action.None })
        // The next window crosses 2.5 s and asks for the cheap step.
        val at = h.onWindow(0f, samples, windowMs, 32 * windowMs)
        assertEquals(Action.AttemptRepair(RepairStep.RECREATE_SAME), at.action)
    }

    @Test fun liveWindowResetsTheDeadClock() {
        val h = MicStreamHealth()
        dead(h, 25, 0)                       // 2.0 s dead
        live(h, 1, 25 * windowMs)            // one live window
        val out = dead(h, 25, 26 * windowMs) // 2.0 s more dead — not 4.0 s continuous
        assertTrue(out.all { it.action == Action.None })
    }

    @Test fun confirmedRepairResetsTheLadder() {
        val h = MicStreamHealth()
        dead(h, 33, 0) // crosses 2.5 s -> AttemptRepair(RECREATE_SAME)
        assertEquals(RepairStep.RECREATE_SAME, h.verifyingStep)
        val confirm = live(h, MicStreamHealth.VERIFY_LIVE_WINDOWS, 33 * windowMs)
        assertEquals(Action.RepairConfirmed(RepairStep.RECREATE_SAME), confirm.last().action)
        // Kill the stream again: the ladder starts over at the cheap step.
        val again = dead(h, 33, 100_000)
        assertEquals(Action.AttemptRepair(RepairStep.RECREATE_SAME), again.last().action)
    }

    @Test fun scatteredLiveWindowsDoNotConfirm() {
        val h = MicStreamHealth()
        dead(h, 33, 0) // AttemptRepair(RECREATE_SAME), verify starts at 2560
        // One short of the proof bar, then dead past the verify
        // deadline: the step must FAIL, not confirm.
        live(h, MicStreamHealth.VERIFY_LIVE_WINDOWS - 1, 33 * windowMs)
        val out = dead(h, 40, 36 * windowMs)
        assertTrue(out.any { it.action == Action.AttemptRepair(RepairStep.SOURCE_VOICE_COMMUNICATION) })
        assertFalse(out.any { it.action is Action.RepairConfirmed })
    }

    @Test fun failedVerificationsWalkTheWholeLadderInOrder() {
        val h = MicStreamHealth()
        val attempts = mutableListOf<RepairStep>()
        var now = 0L
        // Dead stream forever; walk time forward and collect attempts.
        repeat(400) {
            val d = h.onWindow(0f, samples, windowMs, now)
            if (d.action is Action.AttemptRepair) attempts += (d.action as Action.AttemptRepair).step
            now += windowMs
        }
        assertEquals(
            listOf(
                RepairStep.RECREATE_SAME,
                RepairStep.SOURCE_VOICE_COMMUNICATION,
                RepairStep.SOURCE_MIC,
                RepairStep.PREFER_BUILTIN_MIC
            ),
            attempts.take(4)
        )
        assertTrue(h.isExhausted)
    }

    @Test fun exhaustionStopsAttemptsUntilTheCooldownPasses() {
        val h = MicStreamHealth()
        var now = 0L
        var exhaustedAt = -1L
        repeat(400) {
            val d = h.onWindow(0f, samples, windowMs, now)
            if (d.action == Action.RepairExhausted && exhaustedAt < 0) exhaustedAt = now
            now += windowMs
        }
        assertTrue(exhaustedAt > 0)
        // Still dead, inside the 30 s cooldown: no new attempts.
        val during = dead(h, 100, exhaustedAt + 1_000)
        assertTrue(during.all { it.action == Action.None })
        // After the cooldown, a fresh cycle begins at the cheap step.
        val after = dead(h, 40, exhaustedAt + MicStreamHealth.CYCLE_COOLDOWN_MS + 1_000)
        assertTrue(after.any { it.action == Action.AttemptRepair(RepairStep.RECREATE_SAME) })
    }

    @Test fun liveWindowAfterExhaustionRecoversImmediately() {
        val h = MicStreamHealth()
        var now = 0L
        repeat(400) {
            h.onWindow(0f, samples, windowMs, now); now += windowMs
        }
        assertTrue(h.isExhausted)
        live(h, 1, now) // the world changed (headset unplugged, route freed)
        assertFalse(h.isExhausted)
        // A later dead stretch starts a fresh ladder without waiting
        // out any cooldown.
        val out = dead(h, 33, now + 60_000)
        assertEquals(Action.AttemptRepair(RepairStep.RECREATE_SAME), out.last().action)
    }

    @Test fun failedToStartAdvancesTheLadder() {
        val h = MicStreamHealth()
        dead(h, 33, 0)
        assertEquals(RepairStep.RECREATE_SAME, h.verifyingStep)
        val next = h.onRepairFailedToStart(3_000)
        assertEquals(Action.AttemptRepair(RepairStep.SOURCE_VOICE_COMMUNICATION), next.action)
        h.onRepairFailedToStart(3_100)
        val last = h.onRepairFailedToStart(3_200)
        assertEquals(Action.AttemptRepair(RepairStep.PREFER_BUILTIN_MIC), last.action)
        val done = h.onRepairFailedToStart(3_300)
        assertEquals(Action.RepairExhausted, done.action)
        assertTrue(h.isExhausted)
    }

    @Test fun spinReadsAreFlaggedAndStillCountAsDead() {
        val h = MicStreamHealth()
        // Reads returning instantly (0 ms for an 80 ms window) with
        // zero samples: spin suspicion on every directive, and the
        // dead clock still runs on wall time.
        val out = (0 until 40).map { i -> h.onWindow(0f, samples, readElapsedMs = 0L, nowMs = i * 10L) }
        assertTrue(out.all { it.spinSuspected })
        // 40 windows × 10 ms = 400 ms of wall time: no repair yet.
        assertTrue(out.all { it.action == Action.None })
        val later = (0 until 300).map { i -> h.onWindow(0f, samples, readElapsedMs = 0L, nowMs = 400L + i * 10L) }
        assertTrue(later.any { it.action == Action.AttemptRepair(RepairStep.RECREATE_SAME) })
    }

    // ---- Amendment 2: policy-silenced streams (proven root cause) ----

    @Test fun silencedDeadStreamIsClassifiedSilenced() {
        val h = MicStreamHealth()
        val out = deadSilenced(h, 33, 0)
        assertEquals(Action.AttemptRepair(RepairStep.RECREATE_SAME), out.last().action)
        assertTrue(out.last().silenced)
        assertTrue(h.currentEpisodeSilenced)
    }

    @Test fun plainDeadStreamIsClassifiedNotSilenced() {
        val h = MicStreamHealth()
        val out = dead(h, 33, 0)
        assertEquals(Action.AttemptRepair(RepairStep.RECREATE_SAME), out.last().action)
        assertFalse(out.last().silenced)
        assertFalse(h.currentEpisodeSilenced)
    }

    @Test fun silencedClassificationLatchesForTheEpisodeThenResets() {
        val h = MicStreamHealth()
        deadSilenced(h, 10, 0)              // silencing seen early in the episode
        val out = dead(h, 23, 10 * windowMs) // flag stops being sampled, stream still dead
        assertEquals(Action.AttemptRepair(RepairStep.RECREATE_SAME), out.last().action)
        assertTrue("episode stays classified silenced", out.last().silenced)
        // Confirm the repair with live, unsilenced frames: the
        // confirm directive still reports the episode as silenced.
        val confirm = live(h, MicStreamHealth.VERIFY_LIVE_WINDOWS, 33 * windowMs)
        assertTrue(confirm.last().action is Action.RepairConfirmed)
        assertTrue(confirm.last().silenced)
        assertFalse(h.currentEpisodeSilenced)
        // A later, genuinely dead episode classifies clean.
        val again = dead(h, 33, 100_000)
        assertFalse(again.last().silenced)
    }

    @Test fun silencedStreamWalksTheSameLadderAndRetriesAfterCooldown() {
        val h = MicStreamHealth()
        val attempts = mutableListOf<RepairStep>()
        var now = 0L
        var exhaustedAt = -1L
        repeat(400) {
            val d = h.onWindow(0f, samples, windowMs, now, clientSilenced = true)
            if (d.action is Action.AttemptRepair) {
                attempts += (d.action as Action.AttemptRepair).step
                assertTrue(d.silenced)
            }
            if (d.action == Action.RepairExhausted && exhaustedAt < 0) {
                exhaustedAt = now
                assertTrue(d.silenced)
            }
            now += windowMs
        }
        assertEquals(
            listOf(
                RepairStep.RECREATE_SAME,
                RepairStep.SOURCE_VOICE_COMMUNICATION,
                RepairStep.SOURCE_MIC,
                RepairStep.PREFER_BUILTIN_MIC
            ),
            attempts.take(4)
        )
        assertTrue(exhaustedAt > 0)
        // The holder may release minutes later: after the cooldown
        // a fresh cycle retries the preferred path, still latched
        // as the same silenced episode.
        val after = deadSilenced(h, 40, exhaustedAt + MicStreamHealth.CYCLE_COOLDOWN_MS + 1_000)
        assertTrue(after.any { it.action == Action.AttemptRepair(RepairStep.RECREATE_SAME) && it.silenced })
    }

    @Test fun silencedLiveWindowsDoNotVerify() {
        val h = MicStreamHealth()
        dead(h, 33, 0) // AttemptRepair(RECREATE_SAME), verify from 2560
        // Frames carrying sound but still flagged silenced must
        // NOT confirm — success is live AND not silenced. Past
        // the verify deadline the step fails and the ladder moves.
        val flagged = feed(h, 40, 33 * windowMs, rms = 0.02f, silenced = true)
        assertFalse(flagged.any { it.action is Action.RepairConfirmed })
        assertTrue(flagged.any { it.action == Action.AttemptRepair(RepairStep.SOURCE_VOICE_COMMUNICATION) })
        // The same frames without the flag confirm the new step.
        val clean = live(h, MicStreamHealth.VERIFY_LIVE_WINDOWS, 33 * windowMs + 40 * windowMs)
        assertEquals(Action.RepairConfirmed(RepairStep.SOURCE_VOICE_COMMUNICATION), clean.last().action)
    }

    @Test fun silenceMessageSelection() {
        assertEquals(
            "Microphone: another app is using the microphone in call mode right now — " +
                "Ilona will hear you again when it finishes.",
            MicStreamHealth.silenceMessage(silenced = true)
        )
        assertEquals(
            "Microphone: seems silent. Check nothing is covering it, or try speaking closer.",
            MicStreamHealth.silenceMessage(silenced = false)
        )
    }

    @Test fun fastReadsWithRealAudioAreNotSpin() {
        // A backlog dump: reads return quickly but carry real sound.
        // Live audio is never spin and never dead.
        val h = MicStreamHealth()
        val out = (0 until 100).map { i -> h.onWindow(0.02f, samples, readElapsedMs = 1L, nowMs = i * 10L) }
        assertTrue(out.all { !it.spinSuspected && it.action == Action.None })
    }
}
