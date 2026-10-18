package com.app.nuri.dashboard

import com.app.nuri.config.NuriSettings
import com.app.nuri.unstuck.CheckInRecord
import com.app.nuri.unstuck.ConfusionDiary
import com.app.nuri.unstuck.DiaryEntry
import com.app.nuri.unstuck.MedConfirmation
import com.app.nuri.unstuck.SafetyAlert

/** Caregiver dashboard data (brief SS2/SS4). Framing law: "conversations,
 *  not surveillance." Visibility is tier-scoped; Independent shows nothing
 *  unless Hybrid was chosen. */
data class DashboardAlert(val title: String, val detail: String, val whenText: String)
data class ActivityEntry(val what: String, val whenText: String)
data class DashboardData(
    val weeklySummary: String? = null, val dailySummary: String? = null,
    val alerts: List<DashboardAlert> = emptyList(), val activityLog: List<ActivityEntry> = emptyList(),
    val noDataReason: String? = null
)
interface DashboardRepository { fun dataFor(tier: NuriSettings.Tier, accessMode: NuriSettings.AccessMode): DashboardData }

/**
 * REAL dashboard repository (sweep #14 — replaces the Phase D sample
 * repository): composed from Nuri's actual records — check-ins, medicine
 * confirmations, safety alerts, and the confusion diary. Empty records
 * produce honest empty states, never filler. The diary feeds the
 * dashboard only when the access mode makes it caregiver-visible
 * (ConfusionDiary.caregiverVisible); in Independent mode the dashboard
 * shows nothing at all.
 */
class RealDashboardRepository(
    private val checkIns: List<CheckInRecord>,
    private val medConfirmations: List<MedConfirmation>,
    private val safetyAlerts: List<SafetyAlert>,
    private val diary: List<DiaryEntry>,
    private val todayKey: String,
) : DashboardRepository {
    override fun dataFor(tier: NuriSettings.Tier, accessMode: NuriSettings.AccessMode): DashboardData {
        if (accessMode == NuriSettings.AccessMode.INDEPENDENT || !ConfusionDiary.caregiverVisible(accessMode)) {
            return DashboardData(noDataReason = "Nothing is shared. This Nuri is set to 'Just me', so there is no dashboard information at all.")
        }
        val todayCheckIn = checkIns.lastOrNull { it.dayKey == todayKey }
        val todayMeds = medConfirmations.filter { it.dayKey == todayKey }
        val daily = buildString {
            append(
                when {
                    todayCheckIn != null -> "Morning check-in done at ${todayCheckIn.timeText} — slept ${todayCheckIn.sleep.lowercase()}, mood ${todayCheckIn.mood.lowercase()}, appetite: ${todayCheckIn.appetite.lowercase()}."
                    else -> "No morning check-in yet today."
                },
            )
            append(
                when {
                    todayMeds.isEmpty() -> " No medicine confirmations recorded today."
                    else -> " Medicine confirmed: ${todayMeds.joinToString(", ") { "${it.entryName} at ${it.timeText}" }}."
                },
            )
        }
        val weekly = "Help sessions in the diary: ${diary.count { it.outcome.startsWith("Resolved") }} resolved, " +
            "${diary.count { it.outcome.startsWith("Asked") }} times a person was asked, " +
            "${checkIns.size} morning check-in(s) on record in total."
        return DashboardData(
            weeklySummary = weekly,
            dailySummary = daily,
            alerts = safetyAlerts.takeLast(5).reversed().map { DashboardAlert(it.title, it.detail, "${it.dayKey} · ${it.timeText}") },
            activityLog = diary.takeLast(10).reversed().map { ActivityEntry("${it.what} — ${it.outcome}", "${it.dayKey} · ${it.timeText}") },
        )
    }
}
