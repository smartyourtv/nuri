package com.app.nuri.diagnostics

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Diagnostics screen — scaffold version of Scout's diagnostics card pattern.
 * Plain language only; the senior (or caregiver) can screenshot this screen
 * instead of describing a failure.
 */
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    diagnosticsViewModel: DiagnosticsViewModel = viewModel()
) {
    val state by diagnosticsViewModel.state.collectAsState()
    Column(
        // verticalScroll: readability standard — check cards must scroll,
        // never clip, at large system font scales (brief design laws).
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Health check", style = MaterialTheme.typography.headlineMedium)
        state.checks.forEach { check ->
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("${check.name}: ${check.status}", style = MaterialTheme.typography.titleMedium)
                    Text(check.detail)
                }
            }
        }
        // Voice health (v0.4.0): the live card — listener heartbeat,
        // microphone, wake counters, engine state, output route, and
        // the one-tap by-ear voice self-test. Reads the services
        // themselves (VoiceRuntime), never a saved flag.
        VoiceHealthCard()
        // Appearance self-check (brief SS3): current size step + theme + its
        // measured contrast ratio, read live from the settings repository.
        val diagContext = androidx.compose.ui.platform.LocalContext.current
        val diagRepo = remember(diagContext) { com.app.nuri.repository.SettingsRepository(diagContext.applicationContext) }
        val appearanceTheme by diagRepo.appearanceTheme.collectAsState(initial = com.app.nuri.ui.theme.AppearanceTheme.CLASSIC)
        val fontStep by diagRepo.fontStep.collectAsState(initial = com.app.nuri.ui.theme.FontStep.EASY)
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Appearance: OK", style = MaterialTheme.typography.titleMedium)
                Text("Word size: ${fontStep.label}. Colors: ${appearanceTheme.label}. " +
                    "Words stand out from the background ${"%.1f".format(appearanceTheme.bodyContrastRatio())} to 1 " +
                    "(the easy-to-read target is 7 to 1 or more).")
            }
        }
        // UX rule: the button says what it does, and the result is shown.
        Button(onClick = { diagnosticsViewModel.runSelfTest() }) {
            Text("Check that everything is working")
        }
        if (state.selfTestRan) {
            Text("Check finished — everything above is up to date.")
        }
    }
}
