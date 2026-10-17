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
import com.app.nuri.config.NuriSettings
import com.app.nuri.dashboard.SampleDashboardRepository

/** Caregiver dashboard — real tier-scoped composable over sample data.
 *  Framing is law: conversations, not surveillance. */
@Composable
fun CaregiverDashboardScreen(
    tier: NuriSettings.Tier,
    accessMode: NuriSettings.AccessMode,
    modifier: Modifier = Modifier
) {
    val data = remember(tier, accessMode) { SampleDashboardRepository().dataFor(tier, accessMode) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Family summary", style = MaterialTheme.typography.headlineMedium)
        Text("This is a gentle summary of conversations and check-ins — not surveillance. Sample wording only in this build; real records are not collected yet.")
        data.noDataReason?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        data.weeklySummary?.let { Card { Column(Modifier.padding(16.dp)) { Text("This week", style = MaterialTheme.typography.titleMedium); Text(it) } } }
        data.dailySummary?.let { Card { Column(Modifier.padding(16.dp)) { Text("Today", style = MaterialTheme.typography.titleMedium); Text(it) } } }
        if (data.alerts.isNotEmpty()) {
            Text("Things worth knowing", style = MaterialTheme.typography.titleLarge)
            data.alerts.forEach { a -> Card { Column(Modifier.padding(16.dp)) { Text(a.title, style = MaterialTheme.typography.titleMedium); Text(a.detail); Text(a.whenText) } } }
        }
        if (data.activityLog.isNotEmpty()) {
            Text("Activity log", style = MaterialTheme.typography.titleLarge)
            data.activityLog.forEach { e -> Text("${e.whenText}: ${e.what}") }
        }
    }
}
