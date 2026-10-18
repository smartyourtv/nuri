package com.app.nuri.dashboard

import com.app.nuri.config.NuriSettings
import com.app.nuri.unstuck.CheckInRecord
import com.app.nuri.unstuck.DiaryEntry
import com.app.nuri.unstuck.MedConfirmation
import com.app.nuri.unstuck.SafetyAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sweep #14: the dashboard is fed by real records, with honest empty states. */
class RealDashboardTest {
    private val repo = RealDashboardRepository(
        checkIns = listOf(CheckInRecord("2026-10-17", "8:00 AM", "Well", "Good", "Yes")),
        medConfirmations = listOf(MedConfirmation("2026-10-17", "8:05 AM", "Metformin")),
        safetyAlerts = listOf(SafetyAlert("2026-10-17", "9:00 AM", "Medicine not confirmed", "Detail.")),
        diary = listOf(DiaryEntry("2026-10-17", "8:10 AM", "Asked for help: no sound", "Resolved")),
        todayKey = "2026-10-17",
    )

    @Test fun independentShowsNothing() {
        val data = repo.dataFor(NuriSettings.Tier.SUPPORTED, NuriSettings.AccessMode.INDEPENDENT)
        assertTrue(data.noDataReason != null)
        assertTrue(data.alerts.isEmpty())
        assertTrue(data.activityLog.isEmpty())
    }

    @Test fun hybridShowsRealRecords() {
        val data = repo.dataFor(NuriSettings.Tier.SUPPORTED, NuriSettings.AccessMode.HYBRID)
        val summary = data.dailySummary
        assertTrue(summary!!.contains("Metformin"))
        assertTrue(summary.contains("check-in done"))
        assertEquals(1, data.alerts.size)
        assertEquals("Medicine not confirmed", data.alerts.first().title)
        assertEquals(1, data.activityLog.size)
    }

    @Test fun emptyRecordsAreHonest() {
        val empty = RealDashboardRepository(emptyList(), emptyList(), emptyList(), emptyList(), "2026-10-17")
            .dataFor(NuriSettings.Tier.SUPPORTED, NuriSettings.AccessMode.HYBRID)
        assertTrue(empty.dailySummary!!.contains("No morning check-in yet"))
        assertTrue(empty.alerts.isEmpty())
    }
}
