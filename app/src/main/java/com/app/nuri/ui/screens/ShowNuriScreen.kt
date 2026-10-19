package com.app.nuri.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.FileProvider
import com.app.nuri.config.FlavorHooks
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.shownuri.GeminiCore
import com.app.nuri.shownuri.ImageQuality
import com.app.nuri.shownuri.PhotoSubject
import com.app.nuri.shownuri.QrSafety
import com.app.nuri.shownuri.ReadAloudComposer
import com.app.nuri.shownuri.ReadabilityGate
import com.app.nuri.shownuri.ShowNuriGuard
import com.app.nuri.shownuri.SpeechPlan
import com.app.nuri.shownuri.VisionResult
import com.app.nuri.shownuri.VisionRouteSelector
import com.app.nuri.shownuri.android.GeminiVisionGateway
import com.app.nuri.shownuri.android.MlKitVisionGateway
import com.app.nuri.shownuri.android.NuriSpeaker
import com.app.nuri.unstuck.AskNaming
import com.app.nuri.unstuck.DiaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Show Nuri (catalog Part C): one big button + talked-through aiming,
 * retake coaching on dark/blurry captures, then READ (OCR read-aloud +
 * plain-language summary) and/or IDENTIFY (labels with stated
 * confidence). Vision route: on-device ML Kit in this build — the
 * gateway seam takes other routes freely (Shawn's 2026-10-17 decision);
 * none other is configured, and the screen says which route answered.
 * Guardrails come from ShowNuriGuard — never faked, never softened here.
 *
 * v0.3.2 honesty pass (Shawn's on-device tests, 2026-10-18):
 *  - GIBBERISH GATE: OCR passes through ReadabilityGate before a
 *    single word is presented. Broken fragments are never shown as a
 *    reading; clean lines are kept, the rest is coached as a retake.
 *  - LABEL SUPPRESSION: scene/category labels are never an answer,
 *    and object labels are only stated at high confidence — below
 *    that, Nuri says she can't tell yet. OCR text evidence (a
 *    remote's own buttons) beats label guesses.
 *  - READ-ALOUD IS REAL: results are spoken by the phone's built-in
 *    voice (interim platform TTS — not the designed voice) when the
 *    Settings toggle is on (default), with Listen again + Stop.
 *  - CLOUD ROUTE (Part D): when a caregiver has entered their own
 *    free Gemini key, eligible photos get a closer look online
 *    first; any failure falls back silently to the gated on-device
 *    answer. Hard-guardrail subjects never leave the phone.
 */
@Composable
fun ShowNuriScreen(
    repository: SettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gateway = remember { MlKitVisionGateway() }
    val speaker = remember { NuriSpeaker(context) }
    val lines = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var lastSubject by remember { mutableStateOf(PhotoSubject.UNKNOWN) }
    var askTarget by remember { mutableStateOf("the caregiver") }

    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    val photoFile = remember { File(File(context.cacheDir, "shownuri").apply { mkdirs() }, "capture.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile) }

    fun speakResult(composed: List<String>, readAloudOn: Boolean) {
        if (SpeechPlan.shouldAutoSpeak(readAloudOn, composed)) {
            speaker.speak(SpeechPlan.utterances(composed))
        }
    }

    fun analyze(bytes: ByteArray) {
        busy = true
        speaker.stop()
        scope.launch {
            askTarget = AskNaming.askTarget(FlavorHooks.askNameOverride(), repository.caregiverName.first())
            val readAloudOn = repository.showNuriReadAloud.first()
            val cloudKey = repository.cloudVisionKey.first()
            val onDevice: VisionResult? = withContext(Dispatchers.IO) { runCatching { gateway.analyze(bytes) }.getOrNull() }
            lines.clear()
            if (onDevice == null) {
                busy = false
                lines += "I couldn't look at that photo. Let's try once more — more light, held flat and steady."
                speakResult(lines, readAloudOn)
                return@launch
            }
            // Subject is classified LOCALLY, before any route is chosen:
            // the hard guardrails never depend on a model behaving.
            val subject = ShowNuriGuard.guessSubject("", onDevice.ocrText)
            lastSubject = subject
            val composed = mutableListOf<String>()
            composed += ShowNuriGuard.decide(subject, askTarget).line
            onDevice.qrPayload?.let { composed += QrSafety.previewLine(it) }
            var routeName = onDevice.routeName

            when (subject) {
                PhotoSubject.LOOSE_PILL -> Unit // the guard line above IS the whole answer; nothing else is safe to add
                PhotoSubject.BODY_OR_WOUND -> {
                    val top = ReadAloudComposer.objectLabels(onDevice.labels).maxByOrNull { it.confidence }
                    if (top != null && top.confidence >= ReadAloudComposer.CONFIDENT) {
                        composed += "What I can describe: it looks like ${top.text.lowercase()}. Describing is all I can do — a person needs to judge it."
                    }
                }
                PhotoSubject.PERSON -> {
                    if (onDevice.ocrText.isNotBlank()) {
                        val gate = ReadabilityGate.assess(onDevice.ocrText)
                        when (gate.verdict) {
                            ReadabilityGate.Verdict.READABLE ->
                                composed += "The writing I can read in it says: ${onDevice.ocrText.take(400)}"
                            ReadabilityGate.Verdict.PARTIAL ->
                                composed += "The writing I can read in it says: ${gate.cleanLines.joinToString(" ").take(400)}"
                            ReadabilityGate.Verdict.NOT_READABLE ->
                                composed += "There's writing in it, but I couldn't read it clearly — a closer photo may help."
                        }
                    }
                }
                PhotoSubject.FOOD -> {
                    val expiry = ReadAloudComposer.expiryLine(onDevice.ocrText)
                    if (expiry != null) composed += expiry
                    else composed += "I couldn't find a date in that photo. Dates hide on the back, the lid, or a crimped edge — turn it and let's try again."
                }
                else -> {
                    // Cloud first when the selector allows it; silent fallback otherwise.
                    var cloud: GeminiCore.CloudAnswer? = null
                    val route = VisionRouteSelector.select(
                        keyPresent = cloudKey.isNotBlank(),
                        networkAvailable = GeminiVisionGateway.networkAvailable(context),
                        subject = subject,
                    )
                    if (route == VisionRouteSelector.Route.CLOUD_FIRST) {
                        cloud = withContext(Dispatchers.IO) {
                            runCatching { GeminiVisionGateway { cloudKey }.analyze(bytes) }.getOrNull()
                        }
                        if (cloud != null) routeName = GeminiCore.ROUTE_NAME
                    } else if (cloudKey.isBlank() && VisionRouteSelector.cloudEligible(subject)) {
                        // First time it matters: leave the gentle hint on
                        // the caregiver surfaces (never nag the senior).
                        repository.setCloudHintPending(true)
                    }
                    composeRouted(subject, onDevice, cloud, composed, askTarget)
                }
            }
            composed += "(Answered by: $routeName.)"
            busy = false
            lines += composed
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            repository.addDiaryEntry(DiaryEntry(key, time, "Show Ilona: ${subject.name.lowercase().replace('_', ' ')}", "Read/described"))
            speakResult(composed, readAloudOn)
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && photoFile.exists()) {
            val bytes = runCatching { photoFile.readBytes() }.getOrNull()
            if (bytes == null) { lines.clear(); lines += "That photo didn't save. Let's try once more." }
            else {
                scope.launch {
                    val (lum, sharp) = withContext(Dispatchers.IO) { gateway.quality(bytes) }
                    val verdict = ImageQuality.assess(lum, sharp)
                    val coaching = ImageQuality.coachingLine(verdict)
                    if (coaching != null && verdict != ImageQuality.Verdict.UNKNOWN) {
                        pendingBytes = bytes
                        lines.clear(); lines += coaching
                        lines += "Or tap 'Use this photo anyway' and I'll do my best with it."
                    } else analyze(bytes)
                }
            }
        } else {
            lines.clear(); lines += "No photo taken — that's fine. Whenever you're ready, tap the big button."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePicture.launch(photoUri)
        else Toast.makeText(context, "The camera needs your permission to take the photo. Nothing else uses it.", Toast.LENGTH_LONG).show()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Show Ilona", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = { speaker.stop(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Back to my home screen", style = MaterialTheme.typography.labelLarge) }
        Text(ImageQuality.AIMING_INTRO, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                takePicture.launch(photoUri)
            } else permissionLauncher.launch(Manifest.permission.CAMERA)
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
            Text(if (busy) "Looking…" else "Take the photo", style = MaterialTheme.typography.labelLarge)
        }
        pendingBytes?.let { bytes ->
            Button(onClick = { pendingBytes = null; analyze(bytes) }, modifier = Modifier.fillMaxWidth()) {
                Text("Use this photo anyway", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = { pendingBytes = null; takePicture.launch(photoUri) }, modifier = Modifier.fillMaxWidth()) {
                Text("Take it again", style = MaterialTheme.typography.labelLarge)
            }
        }
        lines.forEach { line -> Card(Modifier.fillMaxWidth()) { Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp)) } }
        if (lines.isNotEmpty()) {
            Button(onClick = { speaker.speak(SpeechPlan.utterances(lines)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Listen again", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = { speaker.stop() }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop reading", style = MaterialTheme.typography.labelLarge)
            }
            Button(onClick = {
                scope.launch {
                    val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                    repository.addDiaryEntry(DiaryEntry(key, time, "Show Ilona: asked with a photo (${lastSubject.name.lowercase().replace('_', ' ')})", "Asked $askTarget"))
                }
                lines += "Written in the diary for $askTarget — please show them this photo on the phone, too."
            }, modifier = Modifier.fillMaxWidth()) { Text("Ask $askTarget with this photo", style = MaterialTheme.typography.labelLarge) }
        }
        Text("I never identify people by their faces, never name a loose pill, and never call food or medicine safe. If I'm unsure, I say so.", style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Compose the answer for the routable subjects (documents, pill-bottle
 * labels, objects) from the cloud answer when there is one, else from
 * the gated on-device result. Every OCR text — from either route —
 * passes the ReadabilityGate before it is presented as a reading.
 */
private fun composeRouted(
    subject: PhotoSubject,
    onDevice: VisionResult,
    cloud: GeminiCore.CloudAnswer?,
    out: MutableList<String>,
    askTarget: String,
) {
    if (subject == PhotoSubject.PILL_BOTTLE_LABEL) {
        // The job is the label, as written. Cloud read first, gated
        // on-device OCR as the fallback; the guard line already ran.
        val readText = cloud?.readText?.takeIf { it.isNotBlank() } ?: onDevice.ocrText
        presentReading(readText, onDevice, out, askTarget, withSummary = false)
        return
    }

    // TEXT_DOCUMENT / OBJECT / UNKNOWN.
    val evidence = ReadAloudComposer.identifyFromOcr(onDevice.ocrText)
    if (cloud != null) {
        cloud.plainSummary?.let { out += it }
        val readText = cloud.readText?.takeIf { it.isNotBlank() } ?: onDevice.ocrText
        if (readText.isNotBlank() && subject == PhotoSubject.TEXT_DOCUMENT) {
            presentReading(readText, onDevice, out, askTarget, withSummary = false)
        }
        when {
            evidence != null -> out += evidence
            cloud.identification != null -> out += cloud.identification
            subject != PhotoSubject.TEXT_DOCUMENT -> out += ReadAloudComposer.cantTellLine(askTarget)
        }
        return
    }

    // On-device only.
    if (subject == PhotoSubject.TEXT_DOCUMENT) {
        presentReading(onDevice.ocrText, onDevice, out, askTarget, withSummary = true)
        when {
            evidence != null -> out += evidence
            else -> ReadAloudComposer.confidentObjectLine(onDevice.labels)?.let { out += it }
        }
    } else {
        when {
            evidence != null -> out += evidence
            onDevice.labels.isNotEmpty() -> out += ReadAloudComposer.identifyLine(onDevice.labels, askTarget)
            else -> out += ReadAloudComposer.unsureLine(askTarget)
        }
    }
}

/**
 * Present a reading ONLY as far as the gate allows: full text when
 * READABLE (with the letter summary first for long documents),
 * clean lines only when PARTIAL, coaching — never fragments — when
 * NOT_READABLE.
 */
private fun presentReading(
    text: String,
    onDevice: VisionResult,
    out: MutableList<String>,
    askTarget: String,
    withSummary: Boolean,
) {
    if (text.isBlank()) return
    val gate = ReadabilityGate.assess(text)
    when (gate.verdict) {
        ReadabilityGate.Verdict.READABLE -> {
            if (withSummary && text.length > 120) out += ReadAloudComposer.letterSummary(text)
            out += "Here is everything it says, as written: ${text.take(1200)}"
        }
        ReadabilityGate.Verdict.PARTIAL -> {
            val cleanJoined = gate.cleanLines.joinToString(" ")
            if (withSummary && cleanJoined.length > 120) out += ReadAloudComposer.letterSummary(cleanJoined)
            out += "Here are the parts I could read clearly: ${cleanJoined.take(1200)}"
            out += ReadabilityGate.partialNote(gate.droppedLineCount)
        }
        ReadabilityGate.Verdict.NOT_READABLE -> {
            out += ReadabilityGate.notReadableLine(
                ReadabilityGate.looksLikeScreen(text, onDevice.labels.map { it.text })
            )
            // A failed reading can still carry an identification.
            ReadAloudComposer.identifyFromOcr(text)?.let { out += it }
                ?: ReadAloudComposer.confidentObjectLine(onDevice.labels)?.let { out += it }
        }
    }
}
