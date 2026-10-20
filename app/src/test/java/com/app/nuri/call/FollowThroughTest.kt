package com.app.nuri.call

import com.app.nuri.remote.CallKind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §17.6 — the follow-through suite. The central assertion
 * pattern: media decisions ([GateDecision.OPEN_MEDIA]) are only
 * reachable after the announcement has been spoken, in order, and
 * — for check-in calls — only with his explicit answer.
 */
class FollowThroughTest {

    private class FakeSpeaker(var failOn: Int = -1) : AnnouncementGate.Speaker {
        val spoken = mutableListOf<String>()
        override fun speakBlocking(line: String): Boolean {
            spoken += line
            return spoken.size != failOn
        }
    }

    private class ScriptedSignals(vararg script: WindowSignal) : AnnouncementGate.Signals {
        private val queue = ArrayDeque(script.toList())
        var polls = 0
        override fun poll(): WindowSignal {
            polls++
            return if (queue.isEmpty()) WindowSignal.NONE else queue.removeFirst()
        }
    }

    private val noSleep = AnnouncementGate.Sleeper { }

    // ── Scripts name the caregiver + BOTH media (Amendment 3) ────

    @Test
    fun scriptsNameCaregiverAndBothMedia() {
        val checkIn = CallScripts.checkInAnnouncement("Shawn")
        assertTrue(checkIn.contains("Shawn"))
        assertTrue(checkIn.contains("see and hear you"))
        val deliberate = CallScripts.alertDeliberateAnnouncement("Shawn")
        assertTrue(deliberate.contains("see and hear"))
        val passive = CallScripts.alertPassiveAnnouncement("Maria")
        assertTrue(passive.contains("Maria"))
        assertTrue(passive.contains("see and hear you"))
        val noAnswer = CallScripts.caregiverNoAnswer("Shawn", "Lien")
        assertTrue(noAnswer.contains("didn't answer"))
        assertTrue(noAnswer.contains("right here with you"))
    }

    @Test
    fun caregiverNamingFallsBack() {
        assertEquals("Shawn", CallNaming.caregiverDisplayName("Shawn", "Maria"))
        assertEquals("Maria", CallNaming.caregiverDisplayName(null, "Maria"))
        assertEquals("your caregiver", CallNaming.caregiverDisplayName(null, ""))
        assertEquals("your caregiver", CallNaming.caregiverDisplayName(" ", " "))
    }

    @Test
    fun declineAndAnswerPhrases() {
        assertTrue(CallScripts.isDecline("no"))
        assertTrue(CallScripts.isDecline("Stop!"))
        assertTrue(CallScripts.isDecline("goodbye"))
        assertTrue(CallScripts.isDecline("no thanks"))
        assertFalse(CallScripts.isDecline("I know"))
        assertFalse(CallScripts.isDecline("notebook"))
        assertTrue(CallScripts.isAnswer("yes"))
        assertTrue(CallScripts.isAnswer("Hello"))
        assertFalse(CallScripts.isAnswer("yesterday"))
    }

    // ── Check-in gate: participation required (§17.2) ────────────

    @Test
    fun checkInOpensOnlyAfterAnnouncementAndHisAnswer() {
        val speaker = FakeSpeaker()
        val signals = ScriptedSignals(WindowSignal.NONE, WindowSignal.NONE, WindowSignal.ANSWER)
        val gate = AnnouncementGate(speaker, signals, noSleep)
        val decision = gate.runCheckInGate("Shawn")
        assertEquals(GateDecision.OPEN_MEDIA, decision)
        // The announcement was spoken BEFORE the decision existed.
        assertEquals(1, speaker.spoken.size)
        assertEquals(CallScripts.checkInAnnouncement("Shawn"), speaker.spoken.single())
    }

    @Test
    fun checkInDeclineCancelsImmediately() {
        val speaker = FakeSpeaker()
        val signals = ScriptedSignals(WindowSignal.DECLINE)
        val decision = AnnouncementGate(speaker, signals, noSleep).runCheckInGate("Shawn")
        assertEquals(GateDecision.CANCELLED_DECLINED, decision)
        assertEquals(1, speaker.spoken.size)
    }

    @Test
    fun checkInNoAnswerNeverOpens() {
        val speaker = FakeSpeaker()
        val signals = ScriptedSignals()
        val decision = AnnouncementGate(speaker, signals, noSleep).runCheckInGate("Shawn")
        assertEquals(GateDecision.CANCELLED_NO_ANSWER, decision)
        // Announcements repeated at the design's intervals over the window.
        assertEquals(3, speaker.spoken.size)
    }

    @Test
    fun checkInAnnouncementFailureCancelsRatherThanOpeningSilently() {
        val speaker = FakeSpeaker(failOn = 1)
        val signals = ScriptedSignals(WindowSignal.ANSWER)
        val decision = AnnouncementGate(speaker, signals, noSleep).runCheckInGate("Shawn")
        assertEquals(GateDecision.CANCELLED_ANNOUNCE_FAILED, decision)
    }

    // ── Alert gate: grace model (§17.3) ──────────────────────────

    @Test
    fun alertDeliberateGraceTwoAnnouncementsThenOpen() {
        val speaker = FakeSpeaker()
        val signals = ScriptedSignals()
        val decision = AnnouncementGate(speaker, signals, noSleep).runAlertGate("Shawn", deliberate = true)
        assertEquals(GateDecision.OPEN_MEDIA, decision)
        assertEquals(AnnouncementGate.ALERT_DELIBERATE_ANNOUNCEMENTS, speaker.spoken.size)
        assertTrue(speaker.spoken.all { it == CallScripts.alertDeliberateAnnouncement("Shawn") })
    }

    @Test
    fun alertPassiveGraceThreeAnnouncementsThenOpen() {
        val speaker = FakeSpeaker()
        val signals = ScriptedSignals()
        val decision = AnnouncementGate(speaker, signals, noSleep).runAlertGate("Shawn", deliberate = false)
        assertEquals(GateDecision.OPEN_MEDIA, decision)
        assertEquals(AnnouncementGate.ALERT_PASSIVE_ANNOUNCEMENTS, speaker.spoken.size)
    }

    @Test
    fun alertDeclineDuringGraceCancels() {
        val speaker = FakeSpeaker()
        val signals = ScriptedSignals(WindowSignal.NONE, WindowSignal.BUTTON_DECLINE)
        val decision = AnnouncementGate(speaker, signals, noSleep).runAlertGate("Shawn", deliberate = false)
        assertEquals(GateDecision.CANCELLED_DECLINED, decision)
    }

    @Test
    fun alertAnnouncementFailureCancels() {
        val speaker = FakeSpeaker(failOn = 2)
        val signals = ScriptedSignals()
        val decision = AnnouncementGate(speaker, signals, noSleep).runAlertGate("Shawn", deliberate = false)
        assertEquals(GateDecision.CANCELLED_ANNOUNCE_FAILED, decision)
    }

    @Test
    fun designConstantsMatchTheSpec() {
        assertEquals(600_000L, AnnouncementGate.CALL_TIME_BOX_MS) // 10-minute cap
        assertEquals(180_000L, AnnouncementGate.CHECKIN_WINDOW_MS)
        assertEquals(12_000L, AnnouncementGate.ALERT_DELIBERATE_GRACE_MS)
        assertEquals(30_000L, AnnouncementGate.ALERT_PASSIVE_GRACE_MS)
        assertEquals(60_000L, AnnouncementGate.CAREGIVER_RING_MS)
    }

    // ── Policy, media, request, signaling ────────────────────────

    @Test
    fun followThroughPolicyTitles() {
        assertTrue(FollowThroughPolicy.qualifies("Emergency call made"))
        assertTrue(FollowThroughPolicy.qualifies("Fall detected — no answer"))
        assertTrue(FollowThroughPolicy.qualifies("Welfare check — no answer"))
        assertFalse(FollowThroughPolicy.qualifies("Medicine not confirmed"))
        assertFalse(FollowThroughPolicy.qualifies("Show Ilona photo answered"))
        assertTrue(FollowThroughPolicy.isDeliberate("Emergency call made"))
        assertFalse(FollowThroughPolicy.isDeliberate("Fall detected"))
    }

    @Test
    fun mediaPolicyPersonToggleWins() {
        assertEquals(CallMedia.VIDEO_AND_AUDIO, CallMediaPolicy.effective(householdVoiceOnly = false, personVoiceOnly = false))
        assertEquals(CallMedia.VOICE_ONLY, CallMediaPolicy.effective(householdVoiceOnly = true, personVoiceOnly = false))
        assertEquals(CallMedia.VOICE_ONLY, CallMediaPolicy.effective(householdVoiceOnly = false, personVoiceOnly = true))
    }

    @Test
    fun callRequestStaleness() {
        val request = CallRequest("r1", atMs = 1_000L)
        assertFalse(request.isStale(1_000L + AnnouncementGate.REQUEST_STALE_MS))
        assertTrue(request.isStale(1_000L + AnnouncementGate.REQUEST_STALE_MS + 1))
        val fromDoc = CallRequest.fromHouseholdDoc(mapOf("callRequestId" to "r2", "callRequestAt" to 5_000L))
        assertEquals("r2", fromDoc?.id)
        assertEquals(null, CallRequest.fromHouseholdDoc(mapOf("callRequestId" to "", "callRequestAt" to 5_000L)))
        assertEquals(null, CallRequest.fromHouseholdDoc(null))
    }

    @Test
    fun signalCodecRoundTrip() {
        val signal = CallSignal(
            sessionId = "s1", kind = CallKind.ALERT, media = "VIDEO_AND_AUDIO",
            sdp = "v=0 fake-sdp", fromRole = "SENIOR", state = CallSignalState.RINGING, atMs = 42L,
        )
        val fields = CallSignalCodec.encode(signal)
        val decoded = CallSignalCodec.decode(fields)
        assertEquals(signal, decoded)
        assertEquals(signal, CallSignalCodec.forSession(fields, "s1"))
        assertEquals(null, CallSignalCodec.forSession(fields, "other"))
        assertEquals(null, CallSignalCodec.decode(mapOf("callSignal" to "{broken")))
    }
}
