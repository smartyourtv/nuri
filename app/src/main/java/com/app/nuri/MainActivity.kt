package com.app.nuri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.app.nuri.diagnostics.DiagnosticsScreen
import com.app.nuri.ui.screens.HomeScreen
import com.app.nuri.ui.screens.SettingsScreen
import com.app.nuri.ui.theme.NuriTheme

/**
 * Nuri entry point — scaffold shell.
 *
 * Structure mirrors Scout's MainActivity (Compose, bottom navigation) but the
 * senior UX layer is GATED: big-target senior navigation, one-voice home,
 * and the interface-freeze layout land with the gated UX work. What exists
 * now is the frame: Home / Diagnostics / Settings placeholders.
 *
 * INTERFACE FREEZE NOTE: once the senior UX ships and is learned, this
 * navigation shape must not change (brief §3).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Startup breadcrumbs (crash-report rule, 2026-10-17): if Nuri
        // dies during startup, the report shows exactly how far it got.
        com.app.nuri.reports.android.CrashReports.breadcrumbs.mark("main screen starting")
        // Self-update (brief §10): if an update was installed since the last
        // start, resolve it now — self-test, quiet after-note, or caregiver
        // alert. Runs on a background thread; never blocks the first frame.
        com.app.nuri.update.android.PostUpdateCheck.runAsync(this)
        com.app.nuri.reports.android.CrashReports.breadcrumbs.mark("post-update check started")
        setContent {
            NuriTheme {
                NuriShell()
            }
        }
        com.app.nuri.reports.android.CrashReports.breadcrumbs.mark("main screen shown")
    }
}

private enum class NuriTab(val label: String) {
    Home("Home"),
    Diagnostics("Health check"),
    Settings("Settings")
}

@Composable
private fun NuriShell() {
    var selected by remember { mutableStateOf(NuriTab.Home) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NuriTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Text(tab.label.take(1)) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            NuriTab.Home -> HomeScreen(Modifier.padding(padding))
            NuriTab.Diagnostics -> DiagnosticsScreen(Modifier.padding(padding))
            NuriTab.Settings -> SettingsScreen(Modifier.padding(padding))
        }
    }
}
