package com.app.nuri.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.app.nuri.BuildConfig
import com.app.nuri.config.NuriSettings
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.ui.theme.AppearanceTheme
import com.app.nuri.ui.theme.FontStep
import com.app.nuri.update.SelfUpdatePolicy
import com.app.nuri.tier.EffectiveSettingsResolver
import com.app.nuri.tier.PersonToggles
import com.app.nuri.tier.TierDefaults
import com.app.nuri.tts.EmotionProsody
import kotlinx.coroutines.launch

/**
 * Settings — Phase D: emotion-level dropdown wired to SettingsRepository.
 * Same layout shape as the scaffold (interface freeze: extend, don't
 * redesign). UX rule: description under the control, feedback after a change.
 * Guided clamp is shown honestly when it applies.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    // Crash & error reports (standing rule, 2026-10-17): a sub-screen swap
    // inside Settings — the bottom-navigation shape does not change.
    var showReports by remember { mutableStateOf(false) }
    if (showReports) {
        com.app.nuri.reports.CrashReportsScreen(onBack = { showReports = false }, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val storedEmotion by repo.emotionLevel.collectAsState(initial = NuriSettings.DEFAULT_EMOTION_LEVEL)
    val tier by repo.tier.collectAsState(initial = NuriSettings.DEFAULT_TIER)
    var menuOpen by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val effectiveEmotion = if (tier == NuriSettings.Tier.GUIDED) TierDefaults.clampEmotionForGuided(storedEmotion) else storedEmotion
    // Bug-hunt fix (NU-2): resolve from the STORED toggles (incl. gamesAllowed),
    // not a fresh PersonToggles carrying only the emotion level — the old code
    // made the "Games allowed here" line below show the tier default even when
    // the stored toggle said otherwise.
    val storedToggles by repo.personToggles.collectAsState(initial = PersonToggles(emotionLevel = storedEmotion))
    val effective = EffectiveSettingsResolver.resolve(tier, storedToggles)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("How much feeling Nuri shows", style = MaterialTheme.typography.titleLarge)
        Text("This changes how warm and lively Nuri sounds. It never changes Nuri's voice — it is always the same voice.")
        Box {
            Button(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("${effectiveEmotion.label} — tap to change") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                NuriSettings.EmotionLevel.values().forEach { level ->
                    DropdownMenuItem(
                        text = { Text("${level.ordinal + 1}. ${level.label}") },
                        onClick = { scope.launch { repo.setEmotionLevel(level) }; feedback = "Done — Nuri will sound ${level.label.lowercase()} from now on."; menuOpen = false }
                    )
                }
            }
        }
        Text(EmotionProsody.descriptionFor(effectiveEmotion))
        if (tier == NuriSettings.Tier.GUIDED && storedEmotion != effectiveEmotion) {
            Text("For comfort, the Guided setup keeps feeling in the calm range, so Nuri is using ${effectiveEmotion.label} even though ${storedEmotion.label} was picked.")
        }
        if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.titleMedium)
        Text("Games allowed here: ${if (effective.gamesAllowed) "yes" else "no"} (Guided setups never show games or tests).")
        Text("Always listen ('Hey Nuri'), backup, and family summary live in their own sections in this build's code and arrive on this screen with on-device testing — nothing here is pretending to work before it does.")
        // Self-update (brief SS10, Shawn 2026-10-16): opt-in for the public
        // flavor; always on for the private flavor / Managed setups. The
        // executor (scheduled check, verified download, PackageInstaller,
        // post-update self-test) is built; this toggle is the policy it obeys.
        val accessMode by repo.accessMode.collectAsState(initial = NuriSettings.AccessMode.INDEPENDENT)
        val autoToggle by repo.autoUpdateToggle.collectAsState(initial = null)
        val isPrivateFlavor = BuildConfig.FLAVOR == "private"
        val autoOn = SelfUpdatePolicy.autoUpdateEnabled(isPrivateFlavor, accessMode, autoToggle)
        Text("Updates", style = MaterialTheme.typography.titleLarge)
        Text(SelfUpdatePolicy.FIRST_INSTALL_OFFER_TEXT + ".")
        Switch(
            checked = autoOn,
            enabled = !isPrivateFlavor && accessMode != NuriSettings.AccessMode.MANAGED,
            onCheckedChange = { checked -> scope.launch { repo.setAutoUpdateToggle(checked) } }
        )
        Text(SelfUpdatePolicy.autoUpdateReason(isPrivateFlavor, accessMode, autoToggle))
        // Executor bookkeeping (recorded by the background executor, read
        // here): the quiet after-note once an update lands and passes its
        // self-check, and the last automatic check's plain-language result.
        val updateStore = remember(context) { com.app.nuri.update.android.UpdateRuntimeStore(context.applicationContext) }
        updateStore.afterNote()?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        updateStore.lastRunResult()?.let { Text("Last automatic check: $it") }
        // Therapist installs (brief SS5, bug-hunt NU-5): sharing is disclosed,
        // never hidden — the sharing list and its read-together state stay
        // visible here permanently, and can be marked read here if setup
        // never recorded it. Shown for Therapist first-installs only.
        val firstInstallMode by repo.firstInstallMode.collectAsState(initial = null)
        val consentSeen by repo.therapistConsentSeen.collectAsState(initial = false)
        if (com.app.nuri.setup.TherapistConsent.shouldShowSharingCard(firstInstallMode)) {
            Text("What Nuri shares", style = MaterialTheme.typography.titleLarge)
            Text(TherapistSharingText.WHAT_IS_SHARED)
            Text(
                com.app.nuri.setup.TherapistConsent.statusText(
                    com.app.nuri.setup.TherapistConsent.disclosureState(firstInstallMode, consentSeen)
                )
            )
            Text(TherapistSharingText.ASK_ANYTIME)
            if (!consentSeen) {
                Button(
                    onClick = {
                        scope.launch { repo.setTherapistConsentSeen(true) }
                        feedback = "Done — marked as read together."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("We have read this together — mark as read") }
            }
        }
        // Appearance (brief SS3 readability law — adjustable WITHIN the
        // standard): font steps only scale the standard sizes up; themes are
        // curated AAA presets. Choosing applies everywhere at once, so the
        // preview below is the real thing, not a picture of it.
        val appearanceTheme by repo.appearanceTheme.collectAsState(initial = AppearanceTheme.CLASSIC)
        val fontStep by repo.fontStep.collectAsState(initial = FontStep.EASY)
        Text("How Nuri looks", style = MaterialTheme.typography.titleLarge)
        Text("Make the words bigger, or change the colors. Every choice stays easy to read.")
        Surface(color = Color(appearanceTheme.backgroundHex)) {
            Text(
                "This is how Nuri looks. The words you are reading are the real size.",
                color = Color(appearanceTheme.bodyTextHex),
                fontSize = (20 * fontStep.scale).sp,
                lineHeight = (28 * fontStep.scale).sp,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
        Text("Colors:")
        AppearanceTheme.values().forEach { theme ->
            Button(onClick = { scope.launch { repo.setAppearanceTheme(theme) } }, modifier = Modifier.fillMaxWidth()) {
                Text(if (theme == appearanceTheme) "${theme.label} — using this one" else theme.label)
            }
        }
        Text("Word size:")
        FontStep.values().forEach { step ->
            Button(onClick = { scope.launch { repo.setFontStep(step) } }, modifier = Modifier.fillMaxWidth()) {
                Text(if (step == fontStep) "${step.label} — using this one" else step.label)
            }
        }
        // Crash & error reports (standing rule, 2026-10-17): if something
        // goes wrong, Nuri saves a report in the app and as a file in the
        // Downloads folder; repairs Nuri makes to its own saved
        // information are recorded there too. Reports never leave this
        // phone unless the person using Nuri shares one.
        Text("Crash & error reports", style = MaterialTheme.typography.titleLarge)
        Text("If something goes wrong, Nuri saves a report here and in the Downloads folder. Reports never leave this phone unless you share one.")
        Button(onClick = { showReports = true }, modifier = Modifier.fillMaxWidth()) {
            Text("See crash & error reports")
        }
        // v0.3.0 additions (Unstuck family setup, medicine schedule, setup
        // checklists, snapshots, diary, licenses) — appended section; the
        // cards above are unchanged (interface freeze).
        FamilyHelpSetupSection()
    }
}
