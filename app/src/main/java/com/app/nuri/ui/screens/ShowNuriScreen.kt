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
import com.app.nuri.BuildConfig
import com.app.nuri.config.FlavorHooks
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.shownuri.ImageQuality
import com.app.nuri.shownuri.PhotoSubject
import com.app.nuri.shownuri.QrSafety
import com.app.nuri.shownuri.ReadAloudComposer
import com.app.nuri.shownuri.ShowNuriGuard
import com.app.nuri.shownuri.VisionResult
import com.app.nuri.shownuri.android.MlKitVisionGateway
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
    val lines = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var lastSubject by remember { mutableStateOf(PhotoSubject.UNKNOWN) }
    var askTarget by remember { mutableStateOf("the caregiver") }

    val photoFile = remember { File(File(context.cacheDir, "shownuri").apply { mkdirs() }, "capture.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile) }

    fun analyze(bytes: ByteArray) {
        busy = true
        scope.launch {
            askTarget = AskNaming.askTarget(FlavorHooks.askNameOverride(), repository.caregiverName.first())
            val result: VisionResult? = withContext(Dispatchers.IO) { runCatching { gateway.analyze(bytes) }.getOrNull() }
            busy = false
            lines.clear()
            if (result == null) {
                lines += "I couldn't look at that photo. Let's try once more — more light, held flat and steady."
                return@launch
            }
            val subject = ShowNuriGuard.guessSubject("", result.ocrText)
            lastSubject = subject
            val guard = ShowNuriGuard.decide(subject, askTarget)
            lines += guard.line
            result.qrPayload?.let { lines += QrSafety.previewLine(it) }
            when (subject) {
                PhotoSubject.LOOSE_PILL, PhotoSubject.BODY_OR_WOUND, PhotoSubject.PERSON -> {
                    if (subject == PhotoSubject.PERSON && result.ocrText.isNotBlank()) {
                        lines += "The writing I can read in it says: ${result.ocrText.take(400)}"
                    }
                    if (subject == PhotoSubject.BODY_OR_WOUND && result.labels.isNotEmpty()) {
                        lines += "What I can describe: it looks like ${result.labels.first().text.lowercase()}. Describing is all I can do — a person needs to judge it."
                    }
                }
                PhotoSubject.FOOD -> {
                    val expiry = ReadAloudComposer.expiryLine(result.ocrText)
                    if (expiry != null) lines += expiry
                    else lines += "I couldn't find a date in that photo. Dates hide on the back, the lid, or a crimped edge — turn it and let's try again."
                }
                else -> {
                    if (result.ocrText.isNotBlank()) {
                        if (result.ocrText.length > 120) lines += ReadAloudComposer.letterSummary(result.ocrText)
                        lines += "Here is everything it says, as written: ${result.ocrText.take(1200)}"
                    }
                    if (result.labels.isNotEmpty()) lines += ReadAloudComposer.identifyLine(result.labels, askTarget)
                    if (result.ocrText.isBlank() && result.labels.isEmpty()) lines += ReadAloudComposer.unsureLine(askTarget)
                }
            }
            lines += "(Answered by: ${result.routeName}.)"
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            repository.addDiaryEntry(DiaryEntry(key, time, "Show Nuri: ${subject.name.lowercase().replace('_', ' ')}", "Read/described"))
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
        Text("Show Nuri", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to my home screen", style = MaterialTheme.typography.labelLarge) }
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
            Button(onClick = {
                scope.launch {
                    val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                    repository.addDiaryEntry(DiaryEntry(key, time, "Show Nuri: asked with a photo (${lastSubject.name.lowercase().replace('_', ' ')})", "Asked $askTarget"))
                }
                lines += "Written in the diary for $askTarget — please show them this photo on the phone, too. I never send photos anywhere myself."
            }, modifier = Modifier.fillMaxWidth()) { Text("Ask $askTarget with this photo", style = MaterialTheme.typography.labelLarge) }
        }
        Text("I never identify people by their faces, never name a loose pill, and never call food or medicine safe. If I'm unsure, I say so.", style = MaterialTheme.typography.bodyLarge)
    }
}
