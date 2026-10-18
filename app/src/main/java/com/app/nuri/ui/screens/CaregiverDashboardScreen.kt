package com.app.nuri.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings
import com.app.nuri.dashboard.RealDashboardRepository
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.unstuck.CheckInRecord
import com.app.nuri.unstuck.DiaryEntry
import com.app.nuri.unstuck.MedConfirmation
import com.app.nuri.unstuck.SafetyAlert

/** Caregiver dashboard — real records (sweep #14).
 *  Framing is law: conversations, not surveillance. */
@Composable
fun CaregiverDashboardScreen(
    tier: NuriSettings.Tier,
    accessMode: NuriSettings.AccessMode,
    checkIns: List<CheckInRecord>,
    medConfirmations: List<MedConfirmation>,
    safetyAlerts: List<SafetyAlert>,
    diary: List<DiaryEntry>,
    todayKey: String,
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier,
    // v0.3.2 Part D: set when a photo was answered on-device only
    // because no closer-look key exists yet (and still none does).
    cloudHintPending: Boolean = false
) {
    val data = remember(tier, accessMode, checkIns, medConfirmations, safetyAlerts, diary) {
        RealDashboardRepository(checkIns, medConfirmations, safetyAlerts, diary, todayKey).dataFor(tier, accessMode)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Family summary", style = MaterialTheme.typography.headlineMedium)
        Text("This is a gentle summary of conversations and check-ins — not surveillance. It shows Nuri's real records; when there are none yet, it says so.")
        if (cloudHintPending) {
            Card { Column(Modifier.padding(16.dp)) {
                Text("A note about Show Nuri", style = MaterialTheme.typography.titleMedium)
                Text("A photo recently could only be answered on this phone, because the free 'closer look' key isn't set up yet. Adding it — in Settings, at the end of the 'Family & help setup' section — would let Nuri identify many more things. The key is free from Google, and a photo is only sent when someone asks Nuri to look at it.")
            } }
        }
        data.noDataReason?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        data.weeklySummary?.let { Card { Column(Modifier.padding(16.dp)) { Text("This week", style = MaterialTheme.typography.titleMedium); Text(it) } } }
        data.dailySummary?.let { Card { Column(Modifier.padding(16.dp)) { Text("Today", style = MaterialTheme.typography.titleMedium); Text(it) } } }
        if (data.alerts.isNotEmpty()) {
            Text("Things worth knowing", style = MaterialTheme.typography.titleLarge)
            data.alerts.forEach { a -> Card { Column(Modifier.padding(16.dp)) { Text(a.title, style = MaterialTheme.typography.titleMedium); Text(a.detail); Text(a.whenText) } } }
        }
        if (data.activityLog.isNotEmpty()) {
            Text("Help & check-in diary", style = MaterialTheme.typography.titleLarge)
            data.activityLog.forEach { e -> Text("${e.whenText}: ${e.what}") }
        }
        // Flavor-provided caregiver section (family layer in the private
        // edition; renders nothing in the public edition).
        FlavorHooks.CaregiverFamilySection(settingsRepository)
    }
}
