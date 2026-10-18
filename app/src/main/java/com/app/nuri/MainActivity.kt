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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.app.nuri.backup.SnapshotRotation
import com.app.nuri.backup.android.SnapshotStore
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings
import com.app.nuri.diagnostics.DiagnosticsScreen
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.tier.EffectiveSettingsResolver
import com.app.nuri.tier.PersonToggles
import com.app.nuri.ui.screens.FirstInstallScreen
import com.app.nuri.ui.screens.HomeScreen
import com.app.nuri.ui.screens.MatchingGameScreen
import com.app.nuri.ui.screens.SettingsScreen
import com.app.nuri.ui.screens.ShowNuriScreen
import com.app.nuri.ui.screens.UnstuckScreen
import com.app.nuri.ui.theme.NuriTheme
import com.app.nuri.unstuck.MedRecords
import com.app.nuri.unstuck.android.EmergencyInfoCard
import com.app.nuri.unstuck.android.MedReminder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Nuri entry point.
 *
 * v0.3.0: first-launch routing is wired (sweep #6) — until setup is
 * completed, the first-install flow is the app; afterwards the shell
 * below. The shell's shape is unchanged from the scaffold (Home /
 * Health check / Settings bottom navigation — INTERFACE FREEZE: this
 * shape does not change in updates). Unstuck, Show Nuri, the game, and
 * the family summary are sub-screen swaps inside their tabs, exactly
 * like the crash-reports swap in Settings.
 *
 * Effective values: the tier used for behavior resolves through the
 * flavor hook (private edition: the family layer's safety-critical
 * values win silently; public edition: what you see is what applies).
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
                NuriRoot()
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

private enum class HomeSub { HOME, UNSTUCK, UNSTUCK_CHECKIN, SHOWNURI, GAME }

@Composable
private fun NuriRoot() {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    val setupCompleted by repo.setupCompleted.collectAsState(initial = null)

    // Startup side effects (all guarded; failures are recorded, never fatal):
    // medicine reminders armed from the effective schedule; a weekly
    // snapshot when due; the lock-screen emergency card refreshed.
    LaunchedEffect(Unit) {
        runCatching {
            val seenEncoded = MedRecords.encodeSchedule(repo.medSchedule.first())
            val effectiveEncoded = FlavorHooks.effectiveMedScheduleEncoded(context, seenEncoded)
            MedReminder.scheduleAll(context.applicationContext, MedRecords.decodeSchedule(effectiveEncoded))
        }
        runCatching {
            val snapshots = SnapshotStore.list(context.applicationContext)
            if (SnapshotRotation.weeklyDue(snapshots, System.currentTimeMillis())) {
                val payload = "NURI-SNAPSHOT-V1 auto tier=${repo.tier.first()} access=${repo.accessMode.first()} meds=${repo.medSchedule.first().size}"
                SnapshotStore.take(context.applicationContext, "Weekly", payload, System.currentTimeMillis())
            }
        }
        runCatching {
            if (repo.emergencyInfoOn.first()) {
                val info = repo.emergencyInfo.first()
                if (info.isNotBlank()) EmergencyInfoCard.show(context.applicationContext, info)
            }
        }
        runCatching { repo.touchInteraction(System.currentTimeMillis()) }
        // In-app update-check fallback (v0.3.1): only acts when the nightly
        // job is missing and a check is due — see UpdateScheduler.
        runCatching {
            com.app.nuri.update.android.UpdateScheduler.launchFallbackCheckIfDue(context.applicationContext)
        }
    }

    val safetyCardSeen by repo.safetyCardSeen.collectAsState(initial = false)
    when (setupCompleted) {
        null -> Text("Nuri is getting ready…")
        false -> FirstInstallScreen(
            therapistConsentSeen = false,
            onTherapistConsentRead = { },
            safetyCardSeen = safetyCardSeen,
            onSafetyCardSeen = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { repo.setSafetyCardSeen(true) }
            },
            onModeChosen = { mode, access ->
                SetupCompletion.complete(repo, mode, access)
            },
            onFamilySetup = { name, word, spouse, emName, emNumber, sealed, chain ->
                SetupCompletion.familySetup(repo, name, word, spouse, emName, emNumber, sealed, chain)
            },
        )
        true -> NuriShell(repo)
    }
}

/** Setup completion writes (kept out of the composable body for clarity). */
private object SetupCompletion {
    fun complete(repo: SettingsRepository, mode: NuriSettings.FirstInstallMode, access: NuriSettings.AccessMode) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            repo.setFirstInstallMode(mode)
            repo.setAccessMode(access)
            if (mode == NuriSettings.FirstInstallMode.THERAPIST) repo.setTherapistConsentSeen(true)
            repo.setSetupCompleted(true)
        }
    }

    fun familySetup(repo: SettingsRepository, name: String, word: String, spouseModeName: String, emName: String, emNumber: String, sealed: Boolean, chain: Boolean) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            if (name.isNotBlank()) repo.setCaregiverName(name)
            if (word.isNotBlank()) repo.setSafeWord(word)
            runCatching { com.app.nuri.unstuck.SpouseResponseMode.valueOf(spouseModeName) }.getOrNull()?.let { repo.setSpouseMode(it) }
            if (emName.isNotBlank()) repo.setEmergencyContactName(emName)
            if (emNumber.isNotBlank()) repo.setEmergencyContactNumber(emNumber)
            repo.setSealedPaperDone(sealed)
            repo.setRecoveryChainDone(chain)
        }
    }
}

@Composable
private fun NuriShell(repo: SettingsRepository) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(NuriTab.Home) }
    var homeSub by remember { mutableStateOf(HomeSub.HOME) }
    // Text typed into the Home screen's help field (v0.3.1): handed to the
    // Unstuck screen, which runs it through the tolerant matcher on entry.
    var pendingTypedText by remember { mutableStateOf<String?>(null) }
    val seenTier by repo.tier.collectAsState(initial = NuriSettings.DEFAULT_TIER)
    // Effective tier: flavor hook resolution (private: family safety value wins).
    val effectiveTier = remember(seenTier) {
        runCatching { NuriSettings.Tier.valueOf(FlavorHooks.effectiveTierName(context, seenTier.name)) }.getOrDefault(seenTier)
    }
    val toggles by repo.personToggles.collectAsState(initial = PersonToggles())
    val effective = remember(effectiveTier, toggles) { EffectiveSettingsResolver.resolve(effectiveTier, toggles) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NuriTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab; if (tab == NuriTab.Home) homeSub = HomeSub.HOME },
                        icon = { Text(tab.label.take(1)) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            NuriTab.Home -> when (homeSub) {
                HomeSub.HOME -> HomeScreen(
                    repository = repo,
                    onOpenUnstuck = { checkin -> homeSub = if (checkin) HomeSub.UNSTUCK_CHECKIN else HomeSub.UNSTUCK },
                    onOpenShowNuri = { homeSub = HomeSub.SHOWNURI },
                    onOpenGame = { homeSub = HomeSub.GAME },
                    onAskTyped = { text ->
                        // The universal help phrase re-opens the two-path
                        // offer; anything else goes straight to the matcher.
                        if (com.app.nuri.speech.SafetyIntentParser.parse(text) ==
                            com.app.nuri.speech.SafetyIntent.HELP_REQUEST
                        ) {
                            pendingTypedText = null
                        } else {
                            pendingTypedText = text
                        }
                        homeSub = HomeSub.UNSTUCK
                    },
                    gamesAllowedEffective = effective.gamesAllowed,
                    modifier = Modifier.padding(padding),
                )
                HomeSub.UNSTUCK, HomeSub.UNSTUCK_CHECKIN -> UnstuckScreen(
                    repository = repo,
                    tier = effectiveTier,
                    onOpenShowNuri = { homeSub = HomeSub.SHOWNURI },
                    onBack = { homeSub = HomeSub.HOME },
                    startInCheckIn = homeSub == HomeSub.UNSTUCK_CHECKIN,
                    initialText = pendingTypedText,
                    onInitialTextConsumed = { pendingTypedText = null },
                    modifier = Modifier.padding(padding),
                )
                HomeSub.SHOWNURI -> ShowNuriScreen(
                    repository = repo,
                    onBack = { homeSub = HomeSub.HOME },
                    modifier = Modifier.padding(padding),
                )
                HomeSub.GAME -> androidx.compose.foundation.layout.Column(Modifier.padding(padding)) {
                    androidx.compose.material3.OutlinedButton(onClick = { homeSub = HomeSub.HOME }) {
                        Text("Back to my home screen")
                    }
                    MatchingGameScreen(effective = effective)
                }
            }
            NuriTab.Diagnostics -> DiagnosticsScreen(Modifier.padding(padding))
            NuriTab.Settings -> SettingsScreen(Modifier.padding(padding))
        }
    }
}
