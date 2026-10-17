package com.app.nuri.dashboard

import com.app.nuri.config.NuriSettings

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

/** In-memory SAMPLE repository — clearly labelled sample data in the UI.
 *  TODO(real-data): replace with check-in / safety-layer records; until then
 *  nothing here is a real person's data. */
class SampleDashboardRepository : DashboardRepository {
    override fun dataFor(tier: NuriSettings.Tier, accessMode: NuriSettings.AccessMode): DashboardData {
        if (accessMode == NuriSettings.AccessMode.INDEPENDENT) return DashboardData(
            noDataReason = "Nothing is shared. This Nuri is set to 'Just me', so there is no dashboard information at all.")
        return when (tier) {
            NuriSettings.Tier.INDEPENDENT -> DashboardData(
                weeklySummary = "Sample weekly summary: a calm week, regular morning check-ins. (Sample wording only — no real records yet.)",
                alerts = listOf(DashboardAlert("Unusual quiet morning", "Sample alert: no check-in by the usual time. Alerts at this level are only for unusual patterns.", "Sample")))
            NuriSettings.Tier.SUPPORTED -> DashboardData(
                dailySummary = "Sample daily summary: morning check-in done, medicine confirmed. (Sample wording only — no real records yet.)",
                alerts = listOf(DashboardAlert("Medicine not confirmed", "Sample alert: the morning medicine reminder was not confirmed, so it was passed to the caregiver.", "Sample")))
            NuriSettings.Tier.GUIDED -> DashboardData(
                alerts = listOf(DashboardAlert("Asked 'where am I'", "Sample alert: Nuri gave the reassuring answer and let the caregiver know.", "Sample")),
                activityLog = listOf(ActivityEntry("Morning routine anchor completed", "Sample"), ActivityEntry("Evening calm routine started", "Sample")))
        }
    }
}
