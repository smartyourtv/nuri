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
    // Family views (v0.3.1): the caregiver summary, help diary, and
    // licenses were buried at the very end of this long screen in v0.3.0.
    // They are now top-of-Settings sub-screen swaps like the reports view.
    var showDashboard by remember { mutableStateOf(false) }
    var showDiary by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    if (showTerms) {
        TermsView(onBack = { showTerms = false }, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    if (showLicenses) {
        Column(modifier.padding(24.dp)) { LicensesView(onBack = { showLicenses = false }) }
        return
    }
    if (showDiary) {
        Column(modifier) { DiaryView(repo = repo, onBack = { showDiary = false }) }
        return
    }
    if (showDashboard) {
        val dashTier by repo.tier.collectAsState(initial = NuriSettings.DEFAULT_TIER)
        val dashAccess by repo.accessMode.collectAsState(initial = NuriSettings.AccessMode.INDEPENDENT)
        val dashCheckIns by repo.checkIns.collectAsState(initial = emptyList())
        val dashMeds by repo.medConfirmations.collectAsState(initial = emptyList())
        val dashAlerts by repo.safetyAlerts.collectAsState(initial = emptyList())
        val dashDiary by repo.diary.collectAsState(initial = emptyList())
        val dashCloudKey by repo.cloudVisionKey.collectAsState(initial = "")
        val dashCloudHint by repo.cloudHintPending.collectAsState(initial = false)
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = { showDashboard = false }, modifier = Modifier.fillMaxWidth()) { Text("Back to Settings", style = MaterialTheme.typography.labelLarge) }
            CaregiverDashboardScreen(
                tier = dashTier, accessMode = dashAccess, checkIns = dashCheckIns,
                medConfirmations = dashMeds, safetyAlerts = dashAlerts, diary = dashDiary,
                todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
                settingsRepository = repo,
                cloudHintPending = dashCloudHint && dashCloudKey.isBlank(),
            )
        }
        return
    }
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
        // Family & help quick links (v0.3.1) — first block on this screen.
        Text("Family & help", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { showDashboard = true }, modifier = Modifier.fillMaxWidth()) { Text("Family summary (for the caregiver)", style = MaterialTheme.typography.labelLarge) }
        Button(onClick = { showDiary = true }, modifier = Modifier.fillMaxWidth()) { Text("See the help diary (what Nuri helped with)", style = MaterialTheme.typography.labelLarge) }
        Button(onClick = { showLicenses = true }, modifier = Modifier.fillMaxWidth()) { Text("Licenses & attributions", style = MaterialTheme.typography.labelLarge) }
        Text("Family names, the safe word, medicines, and snapshots are set up in the 'Family & help setup' section further down this screen.")
        // Show Nuri read-aloud (v0.3.2): the interim platform voice.
        // Default ON in the store — the Show Nuri promise depends on it.
        val readAloud by repo.showNuriReadAloud.collectAsState(initial = true)
        Text("Reading aloud", style = MaterialTheme.typography.titleLarge)
        Text("When Show Nuri looks at a photo, it reads its answer aloud in the phone's built-in voice, so it can be heard as well as read. Turn this off for quiet answers — the 'Listen again' button on the Show Nuri screen still works either way.")
        Switch(
            checked = readAloud,
            onCheckedChange = { checked -> scope.launch { repo.setShowNuriReadAloud(checked) } }
        )
        Text(if (readAloud) "Reading aloud is on." else "Reading aloud is off. Answers will appear as words on the screen.")
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
        Text("Always listen ('Hey Nuri') and backup arrive on this screen with on-device testing — nothing here is pretending to work before it does. The family summary is at the top of this screen.")
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
        // About Nuri (v0.3.2, legal checklist §5): the permanent safety
        // card + terms + privacy policy. Permanent means permanent —
        // nothing here can be removed or dismissed away.
        Text("About Nuri", style = MaterialTheme.typography.titleLarge)
        androidx.compose.material3.Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(SafetyInfoText.TITLE, style = MaterialTheme.typography.titleMedium)
                Text(SafetyInfoText.BODY)
            }
        }
        Button(onClick = { showTerms = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Terms of use")
        }
        Button(onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SafetyInfoText.PRIVACY_URL)))
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Privacy policy")
        }
        Text("The privacy policy opens on the web. It explains, in plain words, what Nuri keeps on this phone and the one thing that can leave it — a photo you ask Nuri to identify, and only when a caregiver has added the free closer-look key.")
        // v0.3.0 additions (Unstuck family setup, medicine schedule, setup
        // checklists, snapshots, diary, licenses) — appended section; the
        // cards above are unchanged (interface freeze).
        FamilyHelpSetupSection()
    }
}

/** The full Terms of Use, bundled in the app (assets/TERMS.md) so it travels with the binary. */
@Composable
fun TermsView(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val termsText = remember(context) {
        runCatching { context.assets.open("TERMS.md").bufferedReader().use { it.readText() } }
            .getOrDefault("The terms could not be opened on this phone. The same text, TERMS.md, is in both source repositories.")
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Terms of use", style = MaterialTheme.typography.headlineMedium)
        androidx.compose.material3.OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Settings") }
        Text(termsText, style = MaterialTheme.typography.bodyMedium)
    }
}
