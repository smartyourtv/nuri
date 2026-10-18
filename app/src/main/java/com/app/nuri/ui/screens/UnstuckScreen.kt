package com.app.nuri.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.app.nuri.BuildConfig
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.unstuck.AskNaming
import com.app.nuri.unstuck.CallActions
import com.app.nuri.unstuck.CheckInRecord
import com.app.nuri.unstuck.DiaryEntry
import com.app.nuri.unstuck.EngineOutput
import com.app.nuri.unstuck.HelperCatalogDataB
import com.app.nuri.unstuck.HelperDef
import com.app.nuri.unstuck.HelperEngine
import com.app.nuri.unstuck.HelperFacts
import com.app.nuri.unstuck.HelperSession
import com.app.nuri.unstuck.HelpEntry
import com.app.nuri.unstuck.MedConfirmation
import com.app.nuri.unstuck.MedRecords
import com.app.nuri.unstuck.NoMatchLadder
import com.app.nuri.unstuck.SafetyAlert
import com.app.nuri.unstuck.ScamScreen
import com.app.nuri.unstuck.SpouseResponse
import com.app.nuri.unstuck.TolerantMatch
import com.app.nuri.unstuck.android.AndroidDeviceStateGateway
import com.app.nuri.unstuck.android.WhereAmI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class UnstuckMode { ENTRY, DESCRIBE, TOPICS, MIRROR, SESSION, NOMATCH, CHECKIN, SCAMCHECK }

private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun nowTimeText(): String = SimpleDateFormat("h:mm a", Locale.US).format(Date())

/**
 * The Unstuck surface (v0.3.0). Entry is the two-path offer (Shawn,
 * 2026-10-17): SAY it in your own words, or TAKE A PICTURE (Show Nuri).
 * Helpers run the solve ladder: check-first reads, announced AUTO fixes,
 * one step at a time with an "I'm stuck" escape, ASK rung with the
 * flavor's wording. Intent matching is tolerant (never exact-phrase);
 * a fair guess is mirrored back for confirmation; no match never
 * dead-ends. Every session lands in the confusion diary.
 */
@Composable
fun UnstuckScreen(
    repository: SettingsRepository,
    tier: NuriSettings.Tier,
    onOpenShowNuri: () -> Unit,
    onBack: () -> Unit,
    startInCheckIn: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val askOverride = FlavorHooks.askNameOverride()
    var mode by remember { mutableStateOf(if (startInCheckIn) UnstuckMode.CHECKIN else UnstuckMode.ENTRY) }
    val lines = remember { mutableStateListOf<String>() }
    var session by remember { mutableStateOf<HelperSession?>(null) }
    var sessionDone by remember { mutableStateOf(false) }
    var askOffer by remember { mutableStateOf<EngineOutput.Ask?>(null) }
    var escalateOffer by remember { mutableStateOf(false) }
    var describeText by remember { mutableStateOf("") }
    var mirrorMatch by remember { mutableStateOf<TolerantMatch.Match?>(null) }
    var noMatchChoices by remember { mutableStateOf<List<HelperDef>>(emptyList()) }
    var caregiverName by remember { mutableStateOf("") }
    var checkinStep by remember { mutableStateOf(0) }
    val checkinAnswers = remember { mutableStateListOf<String>() }
    var scamText by remember { mutableStateOf("") }

    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(context, if (granted) "Calling is ready — tap the call button again." else "No problem — the dialer will open with the number filled in.", Toast.LENGTH_LONG).show()
    }

    fun placeCall(number: String, direct: Boolean) {
        val intent = if (direct && ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        } else Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        runCatching { context.startActivity(intent) }
    }

    fun logDiary(what: String, outcome: String) {
        scope.launch { repository.addDiaryEntry(DiaryEntry(todayKey(), nowTimeText(), what, outcome)) }
    }

    suspend fun buildFacts(): HelperFacts {
        val name = repository.caregiverName.first()
        val spouseMode = repository.spouseMode.first()
        val spouseScript = repository.spouseScript.first()
        val confirmations = repository.medConfirmations.first().filter { it.dayKey == todayKey() }
        val schedule = repository.medSchedule.first()
        val gateway = AndroidDeviceStateGateway(context)
        val snapshot = withContext(Dispatchers.IO) { runCatching { gateway.read() }.getOrNull() }
        return HelperFacts(
            snapshot = snapshot,
            todayMedsConfirmed = confirmations,
            safeWordSet = repository.safeWord.first().isNotBlank(),
            spousePlan = SpouseResponse.plan(spouseMode, spouseScript, askOverride, name),
            askOverrideName = askOverride,
            caregiverName = name,
            medsDueToday = schedule.map { it.nameAsWritten },
        )
    }

    fun startHelper(helper: HelperDef) {
        scope.launch {
            val facts = buildFacts()
            val s = HelperEngine.start(helper, facts)
            session = s; sessionDone = false; askOffer = null; escalateOffer = false
            lines.clear()
            // AUTO rung: apply the safe fixes, announced, verified by re-read.
            val gateway = AndroidDeviceStateGateway(context)
            for (fix in s.autoFixCandidates()) {
                val outcome = withContext(Dispatchers.IO) { runCatching { gateway.apply(fix) }.getOrNull() }
                if (outcome != null && outcome.applied) lines += "Done: ${outcome.detail}"
            }
            for (out in s.opening()) {
                when (out) {
                    is EngineOutput.Say -> lines += out.text
                    is EngineOutput.Ask -> { askOffer = out }
                    is EngineOutput.Escalate -> { lines += out.line; escalateOffer = true }
                    is EngineOutput.Done -> { sessionDone = true; logDiary("Asked for help: ${helper.title}", out.outcome) }
                    is EngineOutput.AutoFix -> {}
                }
            }
            mode = UnstuckMode.SESSION
        }
    }

    fun handleOutputs(outs: List<EngineOutput>, helper: HelperDef) {
        for (out in outs) {
            when (out) {
                is EngineOutput.Say -> lines += out.text
                is EngineOutput.Ask -> askOffer = out
                is EngineOutput.Escalate -> { lines += out.line; escalateOffer = true }
                is EngineOutput.Done -> { sessionDone = true; logDiary("Asked for help: ${helper.title}", out.outcome) }
                is EngineOutput.AutoFix -> {}
            }
        }
    }

    LaunchedEffect(Unit) { caregiverName = repository.caregiverName.first() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("I need help", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to my home screen", style = MaterialTheme.typography.labelLarge) }

        when (mode) {
            UnstuckMode.ENTRY -> {
                Text(HelpEntry.OFFER_LINE, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { mode = UnstuckMode.DESCRIBE }, modifier = Modifier.fillMaxWidth()) {
                    Text(HelpEntry.SAY_PATH_LABEL, style = MaterialTheme.typography.labelLarge)
                }
                Text(HelpEntry.SAY_PATH_DETAIL, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenShowNuri, modifier = Modifier.fillMaxWidth()) {
                    Text(HelpEntry.PHOTO_PATH_LABEL, style = MaterialTheme.typography.labelLarge)
                }
                Text(HelpEntry.PHOTO_PATH_DETAIL, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { mode = UnstuckMode.TOPICS }, modifier = Modifier.fillMaxWidth()) {
                    Text("See the topics", style = MaterialTheme.typography.labelLarge)
                }
                Text("Quick things:", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { mode = UnstuckMode.CHECKIN }, modifier = Modifier.fillMaxWidth()) { Text("Morning check-in", style = MaterialTheme.typography.labelLarge) }
                Button(onClick = {
                    scope.launch {
                        val schedule = repository.medSchedule.first()
                        val today = repository.medConfirmations.first().filter { it.dayKey == todayKey() }
                        val due = schedule.firstOrNull { e -> today.none { it.entryName.equals(e.nameAsWritten, ignoreCase = true) } }
                        val name = due?.nameAsWritten ?: "medicine"
                        repository.addMedConfirmation(MedConfirmation(todayKey(), nowTimeText(), name))
                        logDiary("Took medicine: $name", "Recorded")
                        lines.clear(); lines += "Written down: you took your $name at ${nowTimeText()}. Well done."
                        session = null; mode = UnstuckMode.SESSION; sessionDone = true
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("I took my medicine", style = MaterialTheme.typography.labelLarge) }
                Button(onClick = {
                    startHelper(HelperCatalogDataB.combined.first { it.number == 9 })
                }, modifier = Modifier.fillMaxWidth()) { Text("Did I take my pills?", style = MaterialTheme.typography.labelLarge) }
                Button(onClick = {
                    scope.launch {
                        val answer = withContext(Dispatchers.IO) { WhereAmI.answer(context) }
                        lines.clear(); lines += answer.line
                        if (tier == NuriSettings.Tier.GUIDED) {
                            repository.addSafetyAlert(SafetyAlert(todayKey(), nowTimeText(), "Asked 'where am I'", "Location answer given: ${answer.locality ?: "no street name"}."))
                        }
                        logDiary("Asked: where am I", "Answered")
                        session = null; mode = UnstuckMode.SESSION; sessionDone = true
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Where am I?", style = MaterialTheme.typography.labelLarge) }
                Button(onClick = { mode = UnstuckMode.SCAMCHECK }, modifier = Modifier.fillMaxWidth()) { Text("Check a message for scams", style = MaterialTheme.typography.labelLarge) }
            }

            UnstuckMode.DESCRIBE -> {
                Text(HelpEntry.DESCRIBE_PROMPT, style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(value = describeText, onValueChange = { describeText = it },
                    label = { Text("What's happening?", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = {
                    // Voice-intent seam: the universal help phrase re-opens the offer.
                    if (com.app.nuri.speech.SafetyIntentParser.parse(describeText) == com.app.nuri.speech.SafetyIntent.HELP_REQUEST) {
                        mode = UnstuckMode.ENTRY; return@Button
                    }
                    val matches = HelperEngine.matchAll(describeText)
                    val best = matches.firstOrNull()
                    when {
                        best == null || best.confidence == TolerantMatch.Confidence.NONE -> {
                            noMatchChoices = matches.map { it.helper }.ifEmpty { NoMatchLadder.evergreenChoices() }
                            mode = UnstuckMode.NOMATCH
                        }
                        best.confidence == TolerantMatch.Confidence.STRONG -> startHelper(best.helper)
                        else -> { mirrorMatch = best; mode = UnstuckMode.MIRROR }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("That's what's happening", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(onClick = { mode = UnstuckMode.ENTRY }, modifier = Modifier.fillMaxWidth()) { Text("Back", style = MaterialTheme.typography.labelLarge) }
            }

            UnstuckMode.MIRROR -> {
                val m = mirrorMatch
                Text(m?.mirrorLine() ?: "Is this the one?", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { if (m != null) startHelper(m.helper) }, modifier = Modifier.fillMaxWidth()) { Text("Yes, that's the one", style = MaterialTheme.typography.labelLarge) }
                Button(onClick = {
                    noMatchChoices = NoMatchLadder.evergreenChoices(); mode = UnstuckMode.NOMATCH
                }, modifier = Modifier.fillMaxWidth()) { Text("No, it's something else", style = MaterialTheme.typography.labelLarge) }
            }

            UnstuckMode.NOMATCH -> {
                Text(NoMatchLadder.NARROWING_QUESTION, style = MaterialTheme.typography.bodyLarge)
                Text(NoMatchLadder.choiceLine(noMatchChoices.map { it.title }), style = MaterialTheme.typography.bodyLarge)
                noMatchChoices.forEach { def ->
                    Button(onClick = { startHelper(def) }, modifier = Modifier.fillMaxWidth()) { Text(def.title, style = MaterialTheme.typography.labelLarge) }
                }
                Text(NoMatchLadder.PHOTO_PATH_LINE, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenShowNuri, modifier = Modifier.fillMaxWidth()) { Text(HelpEntry.PHOTO_PATH_LABEL, style = MaterialTheme.typography.labelLarge) }
                Button(onClick = {
                    val s = HelperEngine.start(HelperEngine.genericConfusionHelper, HelperFacts(askOverrideName = askOverride, caregiverName = caregiverName))
                    session = s; lines.clear(); handleOutputs(s.opening(), HelperEngine.genericConfusionHelper); mode = UnstuckMode.SESSION
                }, modifier = Modifier.fillMaxWidth()) { Text(AskNaming.buttonLabel(askOverride, caregiverName), style = MaterialTheme.typography.labelLarge) }
            }

            UnstuckMode.TOPICS -> {
                Text("Tap the closest topic. You can always go back.", style = MaterialTheme.typography.bodyLarge)
                HelperCatalogDataB.combined.groupBy { it.family }.forEach { (family, defs) ->
                    Text("Family $family", style = MaterialTheme.typography.titleLarge)
                    defs.forEach { def ->
                        OutlinedButton(onClick = { startHelper(def) }, modifier = Modifier.fillMaxWidth()) {
                            Text(def.title, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                OutlinedButton(onClick = { mode = UnstuckMode.ENTRY }, modifier = Modifier.fillMaxWidth()) { Text("Back", style = MaterialTheme.typography.labelLarge) }
            }

            UnstuckMode.SESSION -> {
                lines.forEach { line -> Card(Modifier.fillMaxWidth()) { Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp)) } }
                val s = session
                if (escalateOffer) {
                    Button(onClick = {
                        scope.launch {
                            val name = FlavorHooks.effectiveEmergencyName(context, repository.emergencyContactName.first())
                            val number = FlavorHooks.effectiveEmergencyNumber(context, repository.emergencyContactNumber.first())
                            val plan = CallActions.emergencyPlan(name, number,
                                ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                            if (plan == null) {
                                Toast.makeText(context, "No emergency contact is set up yet — please call 911 directly if this is an emergency.", Toast.LENGTH_LONG).show()
                            } else { lines += plan.note; placeCall(plan.number, plan.directCall) }
                            repository.addSafetyAlert(SafetyAlert(todayKey(), nowTimeText(), "Emergency call made", "The emergency button was used."))
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Call my emergency contact now", style = MaterialTheme.typography.labelLarge) }
                    Button(onClick = { placeCall("911", direct = false) }, modifier = Modifier.fillMaxWidth()) { Text("Call 911", style = MaterialTheme.typography.labelLarge) }
                }
                if (s != null && !sessionDone) {
                    Button(onClick = { handleOutputs(s.onDone(), s.helper) }, modifier = Modifier.fillMaxWidth()) { Text("I did that / I understand", style = MaterialTheme.typography.labelLarge) }
                    Button(onClick = { handleOutputs(s.onStuck(), s.helper) }, modifier = Modifier.fillMaxWidth()) { Text("I'm stuck", style = MaterialTheme.typography.labelLarge) }
                }
                askOffer?.let { ask ->
                    Text(ask.question, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = {
                        scope.launch {
                            logDiary("Asked a person about: ${s?.helper?.title ?: "help"}", "Asked ${AskNaming.askTarget(askOverride, caregiverName)}")
                            repository.addSafetyAlert(SafetyAlert(todayKey(), nowTimeText(), "Help was asked for", "${s?.helper?.title ?: "A confusion helper"} — the senior chose to ask ${AskNaming.askTarget(askOverride, caregiverName)}. Recorded only; this build sends no messages."))
                        }
                        lines += "Done — I've written it in the diary so ${AskNaming.askTarget(askOverride, caregiverName)} can see it. Please also call or tell them in person — I don't send messages yet."
                        askOffer = null; sessionDone = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(ask.buttonLabel, style = MaterialTheme.typography.labelLarge) }
                }
                if (sessionDone) {
                    Button(onClick = { mode = UnstuckMode.ENTRY; session = null; lines.clear() }, modifier = Modifier.fillMaxWidth()) { Text("All done — back to help home", style = MaterialTheme.typography.labelLarge) }
                }
            }

            UnstuckMode.CHECKIN -> {
                val questions = listOf(
                    "How did you sleep?" to listOf("Well", "Okay", "Not well"),
                    "How is your mood this morning?" to listOf("Good", "Okay", "Low"),
                    "Have you had anything to eat or drink?" to listOf("Yes", "A little", "Not yet"),
                )
                if (checkinStep < questions.size) {
                    Text(questions[checkinStep].first, style = MaterialTheme.typography.titleLarge)
                    questions[checkinStep].second.forEach { option ->
                        Button(onClick = {
                            checkinAnswers += option
                            if (checkinStep == questions.lastIndex) {
                                scope.launch {
                                    val record = CheckInRecord(todayKey(), nowTimeText(),
                                        checkinAnswers.getOrElse(0) { "" }, checkinAnswers.getOrElse(1) { "" }, checkinAnswers.getOrElse(2) { "" })
                                    repository.addCheckIn(record)
                                    val meds = repository.medSchedule.first().map { it.nameAsWritten }
                                    val confirmed = repository.medConfirmations.first().filter { it.dayKey == todayKey() }.map { it.entryName }
                                    lines.clear()
                                    lines += com.app.nuri.unstuck.CheckIns.composeBrief(record, meds, confirmed, null)
                                    logDiary("Morning check-in", "Recorded")
                                    checkinStep = 0; checkinAnswers.clear(); session = null; sessionDone = true; mode = UnstuckMode.SESSION
                                }
                            } else checkinStep++
                        }, modifier = Modifier.fillMaxWidth()) { Text(option, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }

            UnstuckMode.SCAMCHECK -> {
                Text("Paste or type the message here and I'll check it against the patterns scammers use. I only check — I never reply to it.", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(value = scamText, onValueChange = { scamText = it },
                    label = { Text("The message", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = {
                    val risk = ScamScreen.screen(scamText)
                    val warning = ScamScreen.warningLine(risk, AskNaming.askTarget(askOverride, caregiverName))
                    lines.clear()
                    lines += warning ?: "I don't see the usual scam patterns in this one. That isn't a guarantee — if it asks for money, codes, or secrecy later, treat it as a scam and we'll check again."
                    logDiary("Checked a message for scams", risk.name)
                    session = null; sessionDone = true; mode = UnstuckMode.SESSION
                }, modifier = Modifier.fillMaxWidth()) { Text("Check this message", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(onClick = { mode = UnstuckMode.ENTRY }, modifier = Modifier.fillMaxWidth()) { Text("Back", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
