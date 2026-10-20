package com.app.nuri.remote

import kotlinx.serialization.Serializable

/**
 * Remote observation data model (design §6 + order §B1). Everything
 * content-bearing travels inside a single encrypted `payload` field
 * (AES-256-GCM under the household key, see [PayloadCipher]); Firestore
 * documents themselves carry only routing metadata: ids, day keys,
 * timestamps, ack flags, version numbers.
 *
 * The payload types mirror the local records in
 * [com.app.nuri.dashboard.DashboardModels]' sources (CheckInRecord,
 * MedConfirmation, SafetyAlert, DiaryEntry) so the caregiver dashboard
 * can render remote records through the exact same presentation path
 * as local ones.
 */

enum class RemoteEventType { MED_TAKEN, MED_MISSED, CHECKIN, DIARY, SUMMARY, CALL }

/** Alert classes from design §8.3 — every class notifies the caregiver. */
enum class RemoteAlertClass { SAFETY, MED_MISSED, WELFARE }

object AlertRouting {
    /** Push-class (watch service raises a notification) vs silent. */
    fun notifiesCaregiver(alertClass: RemoteAlertClass): Boolean = when (alertClass) {
        RemoteAlertClass.SAFETY, RemoteAlertClass.MED_MISSED, RemoteAlertClass.WELFARE -> true
    }

    /** Events are always silent (dashboard only), per §8.3. */
    fun notifiesCaregiver(eventType: RemoteEventType): Boolean = false

    /** Local safety-alert titles that are really medicine misses. */
    fun alertClassFor(title: String): RemoteAlertClass = when {
        title.contains("medicine", ignoreCase = true) &&
            (title.contains("not confirmed", ignoreCase = true) || title.contains("missed", ignoreCase = true)) ->
            RemoteAlertClass.MED_MISSED
        title.contains("welfare", ignoreCase = true) || title.contains("no answer", ignoreCase = true) ->
            RemoteAlertClass.WELFARE
        else -> RemoteAlertClass.SAFETY
    }
}

@Serializable
data class CheckInPayload(
    val dayKey: String,
    val timeText: String,
    val sleep: String,
    val mood: String,
    val appetite: String,
)

@Serializable
data class MedEventPayload(
    val dayKey: String,
    val timeText: String,
    val entryName: String,
    val taken: Boolean,
)

@Serializable
data class DiaryPayload(
    val dayKey: String,
    val timeText: String,
    val what: String,
    val outcome: String,
)

@Serializable
data class AlertPayload(
    val dayKey: String,
    val timeText: String,
    val title: String,
    val detail: String,
    /** Alert-time location only (design §16.2) — never a feed. */
    val locationText: String? = null,
)

/**
 * The decrypted content of `status/today`. Settings travel as
 * seen-vs-effective pairs (design §1) — the caregiver side renders
 * both; the senior side never sees this document's effective column
 * presented as anything but its own behavior.
 */
@Serializable
data class StatusPayload(
    val checkIn: CheckInPayload? = null,
    val medsToday: List<MedEventPayload> = emptyList(),
    /** Latest diary entry as one plain line ("what — outcome"), for the dashboard's diary glimpse. */
    val diaryLatest: String? = null,
    val seenSettings: Map<String, String> = emptyMap(),
    val effectiveSettings: Map<String, String> = emptyMap(),
    /** Plain-language daily summary line, composed on the senior phone. */
    val summaryLine: String? = null,
)

/**
 * Shadow values as a plain string map (keys mirror the private
 * flavor's shadow store: tier, em_name, em_number, med_sched, font,
 * emotion, games, declined_<kind>). A missing key means "no shadow
 * for that value"; the literal [RemoteShadow.CLEARED] removes one.
 * Interpretation lives in the private FlavorHooks; this type is the
 * transport shape shared by both flavors' protocol code.
 */
@Serializable
data class ShadowPayload(
    val values: Map<String, String> = emptyMap(),
)

/** Canonical keys for the status document's seen/effective settings maps (§3.2). */
object RemoteSettingsKeys {
    const val TIER = "tier"
    const val EMOTION = "emotionLevel"
    const val FONT = "fontStep"
    const val EMERGENCY_NAME = "emergencyContactName"
    const val EMERGENCY_NUMBER = "emergencyContactNumber"
    const val GAMES = "gamesAllowed"
}

object RemoteShadow {
    const val CLEARED = "\u0000CLEARED"
    const val KEY_TIER = "tier"
    const val KEY_EMERGENCY_NAME = "em_name"
    const val KEY_EMERGENCY_NUMBER = "em_number"
    const val KEY_MED_SCHEDULE = "med_sched"
    const val KEY_FONT = "font"
    const val KEY_EMOTION = "emotion"
    const val KEY_GAMES = "games"
    fun declinedKey(kind: String) = "declined_$kind"
}

/** What the senior phone posts back after a shadow document arrives. */
@Serializable
data class ShadowAckPayload(
    val appliedVersion: Int,
    /** Per-key outcome: APPLIED_SILENT / SUGGESTED / DECLINED / NO_CHANGE. */
    val outcomes: Map<String, String> = emptyMap(),
    /** The shadow values this phone now holds (protocol keys) — the caregiver editor seeds from these. */
    val shadowValues: Map<String, String> = emptyMap(),
    val effectiveSettings: Map<String, String> = emptyMap(),
)

object ShadowOutcome {
    const val APPLIED_SILENT = "APPLIED_SILENT"
    const val SUGGESTED = "SUGGESTED"
    const val DECLINED = "DECLINED"
    const val NO_CHANGE = "NO_CHANGE"
}

/**
 * A follow-through / check-in call event (design §17). Only facts
 * travel — when, what kind, how it ended, how long. No audio and
 * no transcription exist anywhere in this feature, by design.
 */
@Serializable
data class CallEventPayload(
    /** CHECKIN (caregiver asked) or ALERT (an alert opened the line). */
    val kind: String,
    /** For ALERT: the alert's title, so the caregiver sees the cause. */
    val alertTitle: String? = null,
    /** ANSWERED / DECLINED / NO_ANSWER / TIMEOUT / FAILED / NO_NUMBER / EXPIRED */
    val outcome: String,
    val durationSec: Int = 0,
    /** SENIOR / CAREGIVER / TIMEOUT / CARRIER — who ended an answered call. */
    val endedBy: String? = null,
    val dayKey: String,
    val timeText: String,
)

object CallOutcome {
    const val ANSWERED = "ANSWERED"
    const val DECLINED = "DECLINED"
    const val NO_ANSWER = "NO_ANSWER"
    const val TIMEOUT = "TIMEOUT"
    const val FAILED = "FAILED"
    const val NO_NUMBER = "NO_NUMBER"

    const val ENDED_BY_SENIOR = "SENIOR"
    const val ENDED_BY_CAREGIVER = "CAREGIVER"
    const val ENDED_BY_TIMEOUT = "TIMEOUT"
    const val ENDED_BY_CARRIER = "CARRIER"
}

object CallKind {
    const val CHECKIN = "CHECKIN"
    const val ALERT = "ALERT"
}

/** A decrypted event as the caregiver side consumes it. */
data class RemoteEvent(
    val id: String,
    val type: RemoteEventType,
    val dayKey: String,
    val createdAtMs: Long,
    val ackByCaregiver: Boolean,
    val checkIn: CheckInPayload? = null,
    val med: MedEventPayload? = null,
    val diary: DiaryPayload? = null,
    val summaryLine: String? = null,
    val call: CallEventPayload? = null,
)

/** A decrypted alert as the caregiver side consumes it. */
data class RemoteAlert(
    val id: String,
    val alertClass: RemoteAlertClass,
    val createdAtMs: Long,
    val ackByCaregiver: Boolean,
    val payload: AlertPayload,
)

/** Decrypted `status/today` plus its plaintext routing metadata. */
data class RemoteStatus(
    val dayKey: String,
    val updatedAtMs: Long?,
    val lastSeenAtMs: Long?,
    /** Order §B1: plaintext routing metadata on status/today. */
    val appVersionName: String?,
    val appVersionCode: Int?,
    val lastUpdateCheckText: String?,
    val payload: StatusPayload?,
)

/** The local binding state for one role on this phone. */
enum class RemoteRole { NONE, SENIOR, CAREGIVER }

data class HouseholdBinding(
    val householdId: String,
    val role: RemoteRole,
    val test: Boolean = false,
)

/** Missing-check-in computation (design §8.3): computed on the caregiver phone. */
object MissingCheckIn {
    const val DEFAULT_CUTOFF_HOUR = 10

    /**
     * True when the cutoff has passed today, no check-in for [todayKey]
     * has arrived, and the alert has not already been raised today.
     */
    fun shouldRaise(
        nowMinutesOfDay: Int,
        cutoffHour: Int,
        todayKey: String,
        checkInDayKey: String?,
        alreadyRaisedForDayKey: String?,
    ): Boolean {
        if (nowMinutesOfDay < cutoffHour * 60) return false
        if (checkInDayKey == todayKey) return false
        if (alreadyRaisedForDayKey == todayKey) return false
        return true
    }

    const val LINE = "No morning check-in yet today."
}
