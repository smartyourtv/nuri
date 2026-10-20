package com.app.nuri.remote

import com.app.nuri.config.NuriSettings
import com.app.nuri.dashboard.ActivityEntry
import com.app.nuri.dashboard.DashboardAlert
import com.app.nuri.dashboard.DashboardData
import com.app.nuri.dashboard.DashboardRepository

/**
 * Renders a REMOTE household's decrypted records into the same
 * [DashboardData] the existing caregiver dashboard renders (design
 * §13.2 — the UI is reused, not forked). Fed by the caregiver
 * repository's listeners; pure mapping, JVM-tested.
 */
class RemoteDashboardRepository(
    private val status: RemoteStatus?,
    private val events: List<RemoteEvent>,
    private val alerts: List<RemoteAlert>,
    private val todayKey: String,
) : DashboardRepository {

    override fun dataFor(tier: NuriSettings.Tier, accessMode: NuriSettings.AccessMode): DashboardData {
        // The household only exists in Hybrid/Managed (§1); the
        // repository is only constructed when a binding exists, but
        // the mode rule is restated here so no caller can leak it.
        if (accessMode == NuriSettings.AccessMode.INDEPENDENT) {
            return DashboardData(noDataReason = "Nothing is shared. This Ilona is set to 'Just me', so there is no dashboard information at all.")
        }
        val payload = status?.payload
        val todayCheckIn = payload?.checkIn?.takeIf { it.dayKey == todayKey }
            ?: events.lastOrNull { it.type == RemoteEventType.CHECKIN && it.dayKey == todayKey }?.checkIn
        val todayMeds = payload?.medsToday?.filter { it.dayKey == todayKey }
            ?: events.filter {
                (it.type == RemoteEventType.MED_TAKEN || it.type == RemoteEventType.MED_MISSED) && it.dayKey == todayKey
            }.mapNotNull { it.med }

        val daily = buildString {
            append(
                when {
                    todayCheckIn != null ->
                        "Morning check-in done at ${todayCheckIn.timeText} — slept ${todayCheckIn.sleep.lowercase()}, mood ${todayCheckIn.mood.lowercase()}, appetite: ${todayCheckIn.appetite.lowercase()}."
                    else -> "No morning check-in yet today."
                },
            )
            append(
                when {
                    todayMeds.isEmpty() -> " No medicine confirmations recorded today."
                    else -> " Medicines today: " + todayMeds.joinToString(", ") {
                        "${it.entryName} at ${it.timeText}" + if (it.taken) "" else " (not confirmed)"
                    } + "."
                },
            )
            payload?.summaryLine?.let { append(" $it") }
        }
        val diaryEvents = events.filter { it.type == RemoteEventType.DIARY }
        val weekly = "Help sessions in the diary: ${diaryEvents.count { it.diary?.outcome?.startsWith("Resolved") == true }} resolved, " +
            "${diaryEvents.count { it.diary?.outcome?.startsWith("Asked") == true }} times a person was asked, " +
            "${events.count { it.type == RemoteEventType.CHECKIN }} morning check-in(s) received in total."
        return DashboardData(
            weeklySummary = weekly,
            dailySummary = daily,
            alerts = alerts.takeLast(5).reversed().map {
                DashboardAlert(it.payload.title, it.payload.detail, "${it.payload.dayKey} · ${it.payload.timeText}")
            },
            activityLog = events.filter { it.type == RemoteEventType.DIARY || it.type == RemoteEventType.CALL }
                .takeLast(10).reversed().map { e ->
                    when {
                        e.diary != null -> ActivityEntry("${e.diary.what} — ${e.diary.outcome}", "${e.dayKey} · ${e.diary.timeText}")
                        e.call != null -> ActivityEntry(callLine(e.call), "${e.dayKey} · ${e.call.timeText}")
                        else -> ActivityEntry("", e.dayKey)
                    }
                },
        )
    }

    companion object {
        /** Plain dashboard wording for a call event (design §17: facts only). */
        fun callLine(call: CallEventPayload): String {
            val what = if (call.kind == CallKind.CHECKIN) "Check-in call" else "Alert call${call.alertTitle?.let { " ($it)" } ?: ""}"
            val outcome = when (call.outcome) {
                CallOutcome.ANSWERED -> {
                    val mins = call.durationSec / 60
                    val secs = call.durationSec % 60
                    val dur = if (mins > 0) "$mins min $secs sec" else "$secs sec"
                    val ended = when (call.endedBy) {
                        CallOutcome.ENDED_BY_SENIOR -> " He ended the call."
                        CallOutcome.ENDED_BY_CAREGIVER -> " You ended the call."
                        CallOutcome.ENDED_BY_TIMEOUT -> " It ended at the time limit."
                        else -> ""
                    }
                    "Talked for $dur.$ended"
                }
                CallOutcome.DECLINED -> "He said no to the call."
                CallOutcome.NO_ANSWER -> "No answer."
                CallOutcome.TIMEOUT -> "Ran to the time limit."
                CallOutcome.NO_NUMBER -> "No phone number was set for the call."
                CallOutcome.FAILED -> "The call could not be made."
                else -> call.outcome
            }
            return "$what — $outcome"
        }
    }
}
