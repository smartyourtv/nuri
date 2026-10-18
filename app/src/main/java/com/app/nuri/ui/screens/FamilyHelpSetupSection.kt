package com.app.nuri.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.app.nuri.BuildConfig
import com.app.nuri.backup.CaregiverMediatedRecovery
import com.app.nuri.backup.SnapshotRotation
import com.app.nuri.backup.android.SnapshotStore
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.tier.TierSlideAdvisor
import com.app.nuri.unstuck.FamilyContact
import com.app.nuri.unstuck.MedScheduleEntry
import com.app.nuri.unstuck.SpouseResponse
import com.app.nuri.unstuck.SpouseResponseMode
import com.app.nuri.unstuck.android.EmergencyInfoCard
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings additions for v0.3.0 (appended section; existing cards are
 * untouched): the Unstuck family setup (caregiver name, family safe word,
 * deceased-spouse response), emergency contact + family quick-calls,
 * medicine schedule, setup checklists (sealed paper copy + Google
 * recovery chain — sweep #44/#29), emergency lock-screen information
 * (#20), snapshots (#28), the help diary, and Licenses (#42).
 */
@Composable
fun FamilyHelpSetupSection() {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val caregiverName by repo.caregiverName.collectAsState(initial = "")
    val safeWord by repo.safeWord.collectAsState(initial = "")
    val spouseMode by repo.spouseMode.collectAsState(initial = SpouseResponseMode.UNSET)
    val spouseScript by repo.spouseScript.collectAsState(initial = "")
    val emergencyName by repo.emergencyContactName.collectAsState(initial = "")
    val emergencyNumber by repo.emergencyContactNumber.collectAsState(initial = "")
    val familyContacts by repo.familyContacts.collectAsState(initial = emptyList())
    val medSchedule by repo.medSchedule.collectAsState(initial = emptyList())
    val accessMode by repo.accessMode.collectAsState(initial = NuriSettings.AccessMode.INDEPENDENT)
    val sealedDone by repo.sealedPaperDone.collectAsState(initial = false)
    val chainDone by repo.recoveryChainDone.collectAsState(initial = false)
    val emergencyInfo by repo.emergencyInfo.collectAsState(initial = "")
    val emergencyInfoOn by repo.emergencyInfoOn.collectAsState(initial = false)
    val usualPlaces by repo.usualPlaces.collectAsState(initial = "")
    val diary by repo.diary.collectAsState(initial = emptyList())
    val tier by repo.tier.collectAsState(initial = NuriSettings.DEFAULT_TIER)
    val cloudVisionKey by repo.cloudVisionKey.collectAsState(initial = "")
    val cloudHintPending by repo.cloudHintPending.collectAsState(initial = false)
    var note by remember { mutableStateOf("") }

    Text("Family & help setup", style = MaterialTheme.typography.titleLarge)
    Text("These are set up once, together. They power the 'I'm confused' help, the medicine records, and the safety buttons. Nuri never stores or speaks passwords — nothing below asks for one.")

    // Caregiver name (feeds the public ASK wording; private always says Shawn)
    var nameField by remember(caregiverName) { mutableStateOf(caregiverName) }
    OutlinedTextField(value = nameField, onValueChange = { nameField = it }, label = { Text("Caregiver's name") }, modifier = Modifier.fillMaxWidth())
    Text("When help is hard, Nuri offers to 'ask ${FlavorHooks.askNameOverride() ?: nameField.ifBlank { "…" }}'.")
    Button(onClick = { scope.launch { repo.setCaregiverName(nameField.trim()) }; note = "Saved the caregiver name." }, modifier = Modifier.fillMaxWidth()) { Text("Save caregiver name") }

    // Family safe word
    var wordField by remember { mutableStateOf("") }
    OutlinedTextField(value = wordField, onValueChange = { wordField = it }, label = { Text("Family safe word (new or replacement)") },
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Text(if (safeWord.isBlank()) "No safe word set yet. Choose a word only your family knows — on any 'emergency' call claiming to be family, Nuri will ask whether the caller knew it. Scammers, even ones copying a voice, won't."
    else "A safe word is set. It is never shown here, never spoken aloud by Nuri, and never written in the diary.")
    Button(onClick = { if (wordField.isNotBlank()) { scope.launch { repo.setSafeWord(wordField.trim()) }; wordField = ""; note = "Safe word saved." } }, modifier = Modifier.fillMaxWidth()) { Text("Save safe word") }

    // Deceased-spouse response
    Text("When they ask for a husband or wife who has passed", style = MaterialTheme.typography.titleLarge)
    Text("The family chooses this. Nuri never improvises facts about them, and there is no made-up default.")
    SpouseResponseMode.values().forEach { m ->
        Button(onClick = {
            // Tap again to deselect (v0.3.2 Part E): tapping the chosen
            // mode clears back to UNSET, persisted like any selection.
            val next = SpouseResponse.toggle(spouseMode, m)
            scope.launch { repo.setSpouseMode(next) }
            note = if (next == SpouseResponseMode.UNSET) "Cleared — no way of answering is chosen now. Nuri will comfort and offer to ask, and never improvise." else "Saved how Nuri answers."
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (m == spouseMode) "${m.name.replace('_', ' ')} — chosen" else m.name.replace('_', ' '))
        }
        Text(SpouseResponse.setupExplanation(m))
    }
    var scriptField by remember(spouseScript) { mutableStateOf(spouseScript) }
    OutlinedTextField(value = scriptField, onValueChange = { scriptField = it }, label = { Text("The family's own words (optional, used exactly as written)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    Button(onClick = { scope.launch { repo.setSpouseScript(scriptField) }; note = "Saved the family's words." }, modifier = Modifier.fillMaxWidth()) { Text("Save the family's words") }

    // Emergency contact + family quick calls
    Text("Emergency contact & family calls", style = MaterialTheme.typography.titleLarge)
    var emName by remember(emergencyName) { mutableStateOf(emergencyName) }
    var emNumber by remember(emergencyNumber) { mutableStateOf(emergencyNumber) }
    OutlinedTextField(value = emName, onValueChange = { emName = it }, label = { Text("Emergency contact name") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = emNumber, onValueChange = { emNumber = it }, label = { Text("Emergency contact number") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { scope.launch { repo.setEmergencyContactName(emName.trim()); repo.setEmergencyContactNumber(emNumber.trim()) }; note = "Saved the emergency contact." }, modifier = Modifier.fillMaxWidth()) { Text("Save emergency contact") }
    var fcName by remember { mutableStateOf("") }
    var fcNumber by remember { mutableStateOf("") }
    OutlinedTextField(value = fcName, onValueChange = { fcName = it }, label = { Text("Family member name (for one-tap calls)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = fcNumber, onValueChange = { fcNumber = it }, label = { Text("Their number") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        if (fcName.isNotBlank() && fcNumber.isNotBlank()) {
            scope.launch { repo.setFamilyContacts(familyContacts + FamilyContact(fcName.trim(), fcNumber.trim())) }
            fcName = ""; fcNumber = ""; note = "Added to the family call buttons."
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Add family call button") }
    familyContacts.forEach { c ->
        Text("${c.name} — ${c.number}")
        OutlinedButton(onClick = { scope.launch { repo.setFamilyContacts(familyContacts - c) } }, modifier = Modifier.fillMaxWidth()) { Text("Remove ${c.name}") }
    }

    // Medicine schedule
    Text("Medicine schedule", style = MaterialTheme.typography.titleLarge)
    Text("Names exactly as written on the bottle. Nuri reminds, records confirmations, and flags a missed dose for the caregiver. Nuri never changes doses.")
    medSchedule.forEach { e ->
        Text("${e.nameAsWritten} — ${e.timeLabel}${if (e.dueHour in 0..23) " (reminder at ${e.dueHour}:00)" else ""}${e.daysLeft?.let { " · about $it day(s) left" } ?: ""}")
        OutlinedButton(onClick = { scope.launch { repo.setMedSchedule(medSchedule - e) } }, modifier = Modifier.fillMaxWidth()) { Text("Remove ${e.nameAsWritten}") }
    }
    var medName by remember { mutableStateOf("") }
    var medTime by remember { mutableStateOf("Morning") }
    var medHour by remember { mutableStateOf("8") }
    var medDays by remember { mutableStateOf("") }
    OutlinedTextField(value = medName, onValueChange = { medName = it }, label = { Text("Medicine name, as written on the bottle") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = medTime, onValueChange = { medTime = it }, label = { Text("Time label (Morning / Evening / 8:00 AM…)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = medHour, onValueChange = { medHour = it.filter { ch -> ch.isDigit() }.take(2) }, label = { Text("Reminder hour (0–23, blank = none)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = medDays, onValueChange = { medDays = it.filter { ch -> ch.isDigit() }.take(3) }, label = { Text("Days of supply left (optional, for refill reminders)") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        if (medName.isNotBlank()) {
            val newEntry = MedScheduleEntry(medName.trim(), medTime.ifBlank { "Morning" }, medHour.toIntOrNull() ?: -1, null, medDays.toIntOrNull())
            scope.launch { repo.setMedSchedule(medSchedule + newEntry) }
            com.app.nuri.unstuck.android.MedReminder.scheduleAll(context.applicationContext, medSchedule + newEntry)
            medName = ""; medDays = ""; note = "Added to the medicine schedule."
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Add medicine") }
    medSchedule.mapNotNull { com.app.nuri.unstuck.MedRecords.refillLine(it) }.forEach { Text(it) }

    // Usual places (catalog #14)
    var placesField by remember(usualPlaces) { mutableStateOf(usualPlaces) }
    OutlinedTextField(value = placesField, onValueChange = { placesField = it }, label = { Text("Usual places (one per line: glasses — kitchen counter)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    Button(onClick = { scope.launch { repo.setUsualPlaces(placesField) }; note = "Saved the usual places." }, modifier = Modifier.fillMaxWidth()) { Text("Save usual places") }

    // Setup checklists (sweep #44 sealed paper + #29 Google recovery chain)
    Text("Setup checklist for the family", style = MaterialTheme.typography.titleLarge)
    androidx.compose.foundation.layout.Row {
        Checkbox(checked = sealedDone, onCheckedChange = { v -> scope.launch { repo.setSealedPaperDone(v) } })
        Text("Sealed paper copy of the Google password, written by hand, sealed, and kept by the caregiver. This is the account password — different from the retired recovery phrase, which no longer exists. Nuri never sees it.")
    }
    androidx.compose.foundation.layout.Row {
        Checkbox(checked = chainDone, onCheckedChange = { v -> scope.launch { repo.setRecoveryChainDone(v) } })
        Text("Google recovery chain: caregiver set as the account's recovery contact, and Inactive Account Manager named, in Google settings.")
    }
    Text("Backup recovery: ${CaregiverMediatedRecovery.statusText(accessMode)}")

    // Emergency info on lock screen (#20)
    Text("Emergency information (lock screen)", style = MaterialTheme.typography.titleLarge)
    var infoField by remember(emergencyInfo) { mutableStateOf(emergencyInfo) }
    OutlinedTextField(value = infoField, onValueChange = { infoField = it }, label = { Text("What helpers should know (name, who to call, important notes — as the family writes it)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    androidx.compose.foundation.layout.Row {
        Checkbox(checked = emergencyInfoOn, onCheckedChange = { v ->
            scope.launch {
                repo.setEmergencyInfo(infoField); repo.setEmergencyInfoOn(v)
                if (v) EmergencyInfoCard.show(context.applicationContext, infoField) else EmergencyInfoCard.hide(context.applicationContext)
            }
        })
        Text("Show this on the lock screen (as a notification anyone can read — only write what helpers truly need).")
    }

    // Snapshots (#28)
    Text("Snapshots", style = MaterialTheme.typography.titleLarge)
    var snapshots by remember { mutableStateOf(SnapshotStore.list(context.applicationContext)) }
    Text(SnapshotRotation.describe(snapshots))
    snapshots.forEach { s -> Text("• ${SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(s.epochMs))} — kept encrypted on this phone") }
    Button(onClick = {
        scope.launch {
            val payload = "NURI-SNAPSHOT-V1 tier=$tier access=$accessMode meds=${medSchedule.size}"
            SnapshotStore.take(context.applicationContext, "On demand", payload, System.currentTimeMillis())
            snapshots = SnapshotStore.list(context.applicationContext)
            note = "Snapshot taken and verified."
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Take a snapshot now") }

    // Tier-slide suggestion (#45)
    TierSlideAdvisor.suggestionFor(tier, diary.count { it.outcome.startsWith("Asked") || it.outcome.contains("stuck", ignoreCase = true) })?.let {
        Text("A gentle suggestion", style = MaterialTheme.typography.titleLarge)
        Text(it)
    }

    // Diary + licenses + dashboard moved to the TOP of Settings in
    // v0.3.1 (discoverability: they were buried at the end of this long
    // section). The buttons live in SettingsScreen now; the views are
    // shared from this file (DiaryView / LicensesView below).
    // A closer look online (v0.3.2 Part D — caregiver's OWN free key;
    // bring-your-own-key: no key exists in the app itself, ever).
    Text("A closer look online (optional, for the caregiver)", style = MaterialTheme.typography.titleLarge)
    Text("Show Nuri reads and identifies on this phone. The phone's own eyes are limited — it is good at reading words, less good at naming things. If you add a free Google Gemini key, photos the person asks about get a closer look online, and Nuri can name many more things.")
    Text("Photos you ask Nuri to identify are sent to Google for a closer look. Nothing is sent until you add a key.")
    if (cloudHintPending && cloudVisionKey.isBlank()) {
        Text("A photo recently could only be answered on this phone. Adding the free key would let Nuri see it much more clearly.", style = MaterialTheme.typography.titleMedium)
    }
    var keyField by remember { mutableStateOf("") }
    OutlinedTextField(value = keyField, onValueChange = { keyField = it }, label = { Text("Google Gemini key (free, from Google AI Studio)") },
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Text(if (cloudVisionKey.isBlank()) "No key is set — Show Nuri answers on this phone only. The key is free: search 'Google AI Studio' in a browser, sign in, choose 'Get API key', and paste it here. It is stored only on this phone and is never shown in full again."
    else "A key is set. Show Nuri will use the closer look online when there is a connection, and answer on this phone when there isn't.")
    Button(onClick = { if (keyField.isNotBlank()) { scope.launch { repo.setCloudVisionKey(keyField) }; keyField = ""; note = "Key saved on this phone." } }, modifier = Modifier.fillMaxWidth()) { Text("Save the key") }
    if (cloudVisionKey.isNotBlank()) {
        OutlinedButton(onClick = { scope.launch { repo.setCloudVisionKey("") }; note = "Key removed. Show Nuri answers on this phone only." }, modifier = Modifier.fillMaxWidth()) { Text("Remove the key") }
    }

    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.titleMedium)
    Text("Vision: Show Nuri reads and identifies on this phone (ML Kit). With a caregiver's key added above, photos can also get a closer look online (Google Gemini). If a photo can't be read either way, Nuri says so and offers to ask ${FlavorHooks.askNameOverride() ?: "the caregiver"} with the photo.")
}

@Composable
fun DiaryView(repo: SettingsRepository, onBack: () -> Unit) {
    val diary by repo.diary.collectAsState(initial = emptyList())
    val alerts by repo.safetyAlerts.collectAsState(initial = emptyList())
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Help diary", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Settings") }
        Text("Everything Nuri helped with, in plain words. In 'Just me' mode this stays on this phone only.")
        if (alerts.isNotEmpty()) {
            Text("Safety notes", style = MaterialTheme.typography.titleLarge)
            alerts.takeLast(5).reversed().forEach { a -> Card { Column(Modifier.padding(16.dp)) { Text(a.title, style = MaterialTheme.typography.titleMedium); Text(a.detail); Text("${a.dayKey} · ${a.timeText}") } } }
        }
        if (diary.isEmpty()) Text("Nothing yet. When help is asked for, it will be written here.")
        diary.takeLast(20).reversed().forEach { e -> Text("${e.dayKey} · ${e.timeText} — ${e.what} (${e.outcome})") }
    }
}

@Composable
fun LicensesView(onBack: () -> Unit) {
    val context = LocalContext.current
    var showFullNotices by remember { mutableStateOf(false) }
    if (showFullNotices) {
        // The full notices file travels inside the app (assets) so the
        // binary distribution carries the texts Apache-2.0 §4 requires
        // (legal checklist §2).
        val noticesText = remember(context) {
            runCatching { context.assets.open("THIRD-PARTY-NOTICES").bufferedReader().use { it.readText() } }
                .getOrDefault("The notices file could not be opened on this phone. The same file, THIRD-PARTY-NOTICES, is in both source repositories.")
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Third-party notices (full text)", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = { showFullNotices = false }, modifier = Modifier.fillMaxWidth()) { Text("Back to Licenses") }
            Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(noticesText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Licenses & attributions", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Settings") }
        Text("Nuri's own source code is licensed GPL-3.0-or-later, with an additional permission (a \"linking exception\") covering the Google libraries inside the app. The full license, the exception, and all third-party notices ship with the source in both project repositories, and the full notices are included in this app under \"Read the full notices\" below.")
        Text("Built with", style = MaterialTheme.typography.titleLarge)
        Text("• ML Kit client libraries (Google) — Apache License 2.0.")
        Text("• The ML Kit models and scanning pipelines built into this app (Google) are Google software, not open source. They are provided under the ML Kit Terms of Service (https://developers.google.com/ml-kit/terms). They read and identify only on this phone — your photos are not sent to Google. ML Kit does send Google small technical reports about its own performance; see the Privacy Policy.")
        Text("• Google Play services libraries, Firebase components, Google Data Transport, odml:image, and Tink (Google) — proprietary Google terms for the Play services libraries; Apache License 2.0 for the rest. Full details: THIRD-PARTY-NOTICES, Part H.")
        Text("• AndroidX, Jetpack Compose, DataStore (Google) — Apache License 2.0.")
        Text("• Kotlin and kotlinx libraries (JetBrains) — Apache License 2.0.")
        Text("Voice models (when the voice engine is connected)", style = MaterialTheme.typography.titleLarge)
        Text("• Voice model by hexgrad (Kokoro-82M), licensed under Apache 2.0. Includes espeak-ng phonemizer data (GPL-3.0-or-later).")
        Text("• Speech-recognition model by NVIDIA (Parakeet TDT 0.6B v2), used under CC-BY-4.0.")
        Text("• Voice model by Supertone Inc. (Supertonic), licensed under OpenRAIL-M (responsible-AI use restrictions apply — no impersonation or deceptive use).")
        Text("Those three voice models are NOT in this build yet — they're listed now so the credits are never an afterthought.")
        Button(onClick = { showFullNotices = true }, modifier = Modifier.fillMaxWidth()) { Text("Read the full notices") }
    }
}
