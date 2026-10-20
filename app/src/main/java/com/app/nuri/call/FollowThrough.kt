package com.app.nuri.call

import com.app.nuri.remote.CallEventPayload
import com.app.nuri.remote.CallKind
import com.app.nuri.remote.CallOutcome

/**
 * Check-in call + alert follow-through — the pure core (design §17).
 *
 * The structural law of this feature, enforced by construction:
 * media (camera/mic/call) is reachable ONLY through
 * [AnnouncementGate]; every path passes the spoken announcement
 * first, and a check-in call additionally requires his explicit
 * answer. There is deliberately no other public entry point that
 * produces a "place media" decision.
 *
 * AnnouncementGate is pure orchestration: the Android layer hands
 * it an announcer (speaks, reports completion), a listener
 * (decline/answer signals during the window), and a clock. Tests
 * drive it with fakes and assert the ordering.
 */

/** Who the announcement names — private flavor: the flavor's name; public: setup name, fallback "your caregiver". */
object CallNaming {
    fun caregiverDisplayName(askNameOverride: String?, setupCaregiverName: String): String =
        askNameOverride?.takeIf { it.isNotBlank() }
            ?: setupCaregiverName.takeIf { it.isNotBlank() }
            ?: "your caregiver"
}

/** Exact announcement scripts (design §17.2/§17.3). [name] is the caregiver display name. */
object CallScripts {

    fun checkInAnnouncement(name: String): String =
        "Hello, it's Ilona. $name would like to talk with you — $name can see and hear you " +
            "through me while you talk. If you'd like to talk, tap the big Talk button, or say yes. " +
            "If not, say no, or tap No — then nothing will happen."

    fun alertDeliberateAnnouncement(name: String): String =
        "$name is being called. You're going to talk with $name through me — $name will be " +
            "able to see and hear you. If you don't want the call, say no, or tap the big No button."

    fun alertPassiveAnnouncement(name: String): String =
        "Hello, it's Ilona. I noticed you didn't answer, so I'm opening a call to $name to " +
            "make sure you're alright. $name will be able to see and hear you through me while " +
            "you talk. If you're alright and you don't want the call, say no, or tap the big No button."

    fun fallbackTelephonyAnnouncement(name: String): String =
        "I couldn't reach $name through me, so I'm calling $name's phone number now. " +
            "You'll be able to hear and talk, just like a normal phone call."

    fun caregiverNoAnswer(name: String, emergencyContactName: String): String =
        "$name didn't answer. The alert is on $name's phone, and it stays there until $name " +
            "sees it. I'm right here with you. If you need help now, tap the big button and " +
            "I'll call $emergencyContactName."

    fun callConnected(name: String): String =
        "You're talking with $name now. The camera is on, so $name can see you. " +
            "You can turn the camera off, or end the call, with the big buttons."

    fun callEndedBySenior(): String = "You ended the call."
    fun callEndedByCaregiver(name: String): String = "$name ended the call."
    fun callTimedOut(): String = "The call has reached its time limit, so it has ended."
    fun noNumberLine(): String =
        "I don't have a phone number for your caregiver yet, so I couldn't place the call. The alert is still recorded."

    /** Voice phrases honored during announcement windows (design §17.2/§17.3). */
    val DECLINE_PHRASES: Set<String> = setOf("no", "stop", "goodbye", "no thanks", "don't call", "do not call")
    val ANSWER_PHRASES: Set<String> = setOf("yes", "hello", "okay", "ok", "talk")

    fun isDecline(spoken: String): Boolean {
        val s = spoken.trim().lowercase().trimEnd('.', '!', ',')
        return DECLINE_PHRASES.any { s == it || s.startsWith("$it ") || s.endsWith(" $it") }
    }

    fun isAnswer(spoken: String): Boolean {
        val s = spoken.trim().lowercase().trimEnd('.', '!', ',')
        return ANSWER_PHRASES.any { s == it || s.startsWith("$it ") }
    }
}

/** Alert titles that trigger follow-through (design §17.3). */
object FollowThroughPolicy {
    fun qualifies(alertTitle: String): Boolean {
        val t = alertTitle.lowercase()
        return t.contains("emergency") ||
            t.contains("fall") ||
            t.contains("welfare") ||
            t.contains("no answer") ||
            t.contains("didn't answer") ||
            t.contains("did not answer")
    }

    /** The emergency trigger was deliberately pressed by the senior (short grace). */
    fun isDeliberate(alertTitle: String): Boolean =
        alertTitle.lowercase().contains("emergency")
}

/** Media resolution (design §17.5): household default yields to the per-person toggle; in-call kill is per-call. */
enum class CallMedia { VIDEO_AND_AUDIO, VOICE_ONLY }

object CallMediaPolicy {
    fun effective(householdVoiceOnly: Boolean, personVoiceOnly: Boolean): CallMedia =
        if (householdVoiceOnly || personVoiceOnly) CallMedia.VOICE_ONLY else CallMedia.VIDEO_AND_AUDIO
}

/** What the announcement window heard / saw. */
enum class WindowSignal { NONE, DECLINE, ANSWER, BUTTON_DECLINE, BUTTON_ANSWER }

enum class GateDecision { OPEN_MEDIA, CANCELLED_DECLINED, CANCELLED_NO_ANSWER, CANCELLED_ANNOUNCE_FAILED }

/**
 * The announcement gate (design §17): runs the announcement cycle
 * for a call and returns the ONLY decisions that may lead to media.
 *
 * - CHECKIN kind: participation required. Cycles announcements at
 *   the design's intervals until ANSWER (open), DECLINE (cancel),
 *   or the window expires (no-answer — media NEVER opens).
 * - ALERT kind: grace model. Announcements repeat per the trigger
 *   type; DECLINE cancels; silence at grace end opens the line.
 *
 * The gate itself speaks via [Speaker] and polls [Signals]; media
 * is not its business — the caller may open media only on
 * [GateDecision.OPEN_MEDIA]. Announcement failure (speaker error)
 * cancels an alert call rather than opening silently — announced,
 * never silent, even in failure.
 */
class AnnouncementGate(
    private val speaker: Speaker,
    private val signals: Signals,
    private val sleeper: Sleeper,
) {
    fun interface Speaker {
        /** Speak [line] to completion. Returns false if speaking failed. */
        fun speakBlocking(line: String): Boolean
    }

    fun interface Signals {
        /** Signal received since the last poll. */
        fun poll(): WindowSignal
    }

    fun interface Sleeper {
        fun sleep(ms: Long)
    }

    companion object {
        // Check-in window (design §17.2): announcements at ~0s, 60s, 120s; window 180s.
        const val CHECKIN_WINDOW_MS = 180_000L
        val CHECKIN_ANNOUNCE_AT_MS = listOf(0L, 60_000L, 120_000L)
        const val CHECKIN_POLL_MS = 500L

        // Alert grace (design §17.3): deliberate 2 announcements / 12 s; passive 3 / 30 s.
        const val ALERT_DELIBERATE_GRACE_MS = 12_000L
        const val ALERT_DELIBERATE_ANNOUNCEMENTS = 2
        const val ALERT_PASSIVE_GRACE_MS = 30_000L
        const val ALERT_PASSIVE_ANNOUNCEMENTS = 3
        const val ALERT_POLL_MS = 500L

        /** The call time-box (Amendment 2: 10 minutes). */
        const val CALL_TIME_BOX_MS = 600_000L

        /** Caregiver ring window for the reverse case (design §17.3). */
        const val CAREGIVER_RING_MS = 60_000L

        /** A call request older than this is stale and ignored (§17.2). */
        const val REQUEST_STALE_MS = 120_000L
    }

    /** Check-in call: announcements + his participation required. */
    fun runCheckInGate(name: String): GateDecision {
        var elapsed = 0L
        var announced = 0
        while (elapsed < CHECKIN_WINDOW_MS) {
            if (announced < CHECKIN_ANNOUNCE_AT_MS.size && elapsed >= CHECKIN_ANNOUNCE_AT_MS[announced]) {
                if (!speaker.speakBlocking(CallScripts.checkInAnnouncement(name))) {
                    return GateDecision.CANCELLED_ANNOUNCE_FAILED
                }
                announced++
            }
            when (signals.poll()) {
                WindowSignal.DECLINE, WindowSignal.BUTTON_DECLINE -> return GateDecision.CANCELLED_DECLINED
                WindowSignal.ANSWER, WindowSignal.BUTTON_ANSWER -> return GateDecision.OPEN_MEDIA
                WindowSignal.NONE -> {}
            }
            sleeper.sleep(CHECKIN_POLL_MS)
            elapsed += CHECKIN_POLL_MS
        }
        return GateDecision.CANCELLED_NO_ANSWER
    }

    /** Alert follow-through: grace model — silence at grace end opens the line. */
    fun runAlertGate(name: String, deliberate: Boolean): GateDecision {
        val line = if (deliberate) {
            CallScripts.alertDeliberateAnnouncement(name)
        } else {
            CallScripts.alertPassiveAnnouncement(name)
        }
        val announcements = if (deliberate) ALERT_DELIBERATE_ANNOUNCEMENTS else ALERT_PASSIVE_ANNOUNCEMENTS
        val grace = if (deliberate) ALERT_DELIBERATE_GRACE_MS else ALERT_PASSIVE_GRACE_MS
        val spacing = grace / announcements
        var elapsed = 0L
        var spoken = 0
        while (elapsed < grace) {
            if (spoken < announcements && elapsed >= spoken * spacing) {
                if (!speaker.speakBlocking(line)) return GateDecision.CANCELLED_ANNOUNCE_FAILED
                spoken++
            }
            when (signals.poll()) {
                WindowSignal.DECLINE, WindowSignal.BUTTON_DECLINE -> return GateDecision.CANCELLED_DECLINED
                else -> {}
            }
            sleeper.sleep(ALERT_POLL_MS)
            elapsed += ALERT_POLL_MS
        }
        return GateDecision.OPEN_MEDIA
    }
}

/** A call request as it rides the household document (§17.1/§17.2). */
data class CallRequest(val id: String, val atMs: Long) {
    fun isStale(nowMs: Long): Boolean = nowMs - atMs > AnnouncementGate.REQUEST_STALE_MS

    companion object {
        fun fromHouseholdDoc(doc: Map<String, Any?>?): CallRequest? {
            if (doc == null) return null
            val id = doc["callRequestId"] as? String ?: return null
            val at = (doc["callRequestAt"] as? Number)?.toLong() ?: return null
            if (id.isBlank()) return null
            return CallRequest(id, at)
        }
    }
}

/** Builds the CALL event payload for an outcome — facts only (§17.5). */
object CallEventFactory {
    fun event(
        kind: String,
        alertTitle: String?,
        outcome: String,
        durationSec: Int = 0,
        endedBy: String? = null,
        dayKey: String,
        timeText: String,
    ): CallEventPayload = CallEventPayload(
        kind = kind,
        alertTitle = alertTitle,
        outcome = outcome,
        durationSec = durationSec,
        endedBy = endedBy,
        dayKey = dayKey,
        timeText = timeText,
    )

    /** Kind constants re-exported for callers that only import this package. */
    val KIND_CHECKIN: String = CallKind.CHECKIN
    val KIND_ALERT: String = CallKind.ALERT
    val OUTCOME_NO_ANSWER: String = CallOutcome.NO_ANSWER
}
