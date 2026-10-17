package com.app.nuri.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase D note: the caregiver dashboard, games, and first-install screens
 * are NO LONGER placeholders — see CaregiverDashboardScreen.kt,
 * MatchingGameScreen.kt, and FirstInstallScreen.kt. These stubs remain only
 * so any old reference still compiles; they are not used in navigation.
 */
@Composable
fun CaregiverDashboardPlaceholder(modifier: Modifier = Modifier) { GatedNote(modifier, "Caregiver Dashboard", "Built in Phase D — see the Family summary screen code.") }
@Composable
fun GamesPlaceholder(modifier: Modifier = Modifier) { GatedNote(modifier, "Games", "Built in Phase D — see the matching shapes game code.") }
@Composable
fun FirstInstallPlaceholder(modifier: Modifier = Modifier) { GatedNote(modifier, "Welcome to Nuri", "Built in Phase D — see the first-install screen code.") }
@Composable
private fun GatedNote(modifier: Modifier, title: String, note: String) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium); Text(note)
    }
}
