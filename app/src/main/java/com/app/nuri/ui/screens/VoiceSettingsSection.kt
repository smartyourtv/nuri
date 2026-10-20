package com.app.nuri.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.core.content.ContextCompat
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.speech.VoiceRuntime
import com.app.nuri.speech.android.AlwaysListenService
import com.app.nuri.tts.VoiceProfiles
import com.app.nuri.tts.android.IlonaSpeaker
import com.app.nuri.tts.android.VoiceModelManager
import kotlinx.coroutines.launch

/**
 * Settings → "Ilona's voice" (v0.4.0).
 *
 * Three honest blocks, in the order a person meets them:
 *  1. HER VOICE — the picker (June by default; tap to hear, tap to
 *     choose; the choice is saved and NEVER reset by an update).
 *  2. THE DOWNLOAD — her voice + understanding arrive once, after
 *     install; states are shown plainly (never "ready" when not).
 *  3. LISTENING — the "Hey Ilona" switch with the live state read
 *     from the listener itself, plus the microphone permission ask
 *     at the moment of turning it on.
 *
 * Readability law applies (the app theme carries the sizes).
 */
@Composable
fun VoiceSettingsSection(repo: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember(context) { IlonaSpeaker.shared(context.applicationContext) }
    val storedVoiceId by repo.singleVoiceId.collectAsState(initial = VoiceProfiles.LEGACY_UNSET)
    val selected = VoiceProfiles.resolve(storedVoiceId)
    val packStatuses by speaker.models.statuses.collectAsState()
    val personToggles by repo.personToggles.collectAsState(initial = com.app.nuri.tier.PersonToggles())
    val alwaysListen = personToggles.alwaysListenEnabled ?: false
    val runtime by VoiceRuntime.state.collectAsState()
    var feedback by remember { mutableStateOf("") }

    Text("Ilona's voice", style = MaterialTheme.typography.titleLarge)
    Text(
        "Ilona speaks with the voice picked here. June is her voice to start with. " +
            "Tap “Hear this voice” to listen, then “Use this voice” to choose it. " +
            "Your choice is saved for good — an update will never change it behind your back."
    )
    VoiceProfiles.VOICES.forEach { voice ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (voice == selected) "${voice.displayName} — using this one" else voice.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Text(voice.blurb)
            Button(
                onClick = { speaker.speakSample(voice) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Hear this voice") }
            if (voice != selected) {
                OutlinedButton(
                    onClick = {
                        scope.launch { repo.setSingleVoiceId(VoiceProfiles.storedValue(voice)) }
                        feedback = "Done — Ilona will use ${voice.displayName.substringBefore(" —")} from now on."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Use this voice") }
            }
        }
    }
    if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.titleMedium)

    // The model download: three packs, each with a truthful state.
    Text("Her voice and understanding (one download)", style = MaterialTheme.typography.titleLarge)
    VoiceModelManager.Pack.values().forEach { pack ->
        val status = packStatuses[pack] ?: speaker.models.status(pack)
        Text(packLine(pack, status))
    }
    val anyMissing = VoiceModelManager.Pack.values().any { !speaker.models.isReady(it) }
    val anyBusy = packStatuses.values.any {
        it.state == VoiceModelManager.State.DOWNLOADING ||
            it.state == VoiceModelManager.State.VERIFYING ||
            it.state == VoiceModelManager.State.EXTRACTING
    }
    if (anyMissing && !anyBusy) {
        Button(
            onClick = { speaker.models.downloadMissing() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Download Ilona's voice (about ${totalDownloadText(speaker.models)})") }
    }
    Text(
        "The download happens once — best on Wi-Fi. After that, Ilona's voice, her listening, " +
            "and her understanding all work on this phone, with no internet needed. Nothing you say " +
            "to Ilona is sent anywhere."
    )

    // Listening.
    Text("Listening for “Hey Ilona”", style = MaterialTheme.typography.titleLarge)
    Text(
        "With listening on, you can just say “Hey Ilona” and then say what you need — no buttons. " +
            "Listening happens on this phone only. The microphone is only used while this is on."
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { repo.setAlwaysListenEnabled(true) }
            AlwaysListenService.start(context)
            feedback = "Listening is on. Say “Hey Ilona” whenever you need her."
        } else {
            feedback = "The microphone wasn't allowed, so listening stays off. You can still type to Ilona, or tap this switch again and allow the microphone."
        }
    }
    Switch(
        checked = alwaysListen,
        onCheckedChange = { checked ->
            if (checked) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    scope.launch { repo.setAlwaysListenEnabled(true) }
                    AlwaysListenService.start(context)
                    feedback = "Listening is on. Say “Hey Ilona” whenever you need her."
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                scope.launch { repo.setAlwaysListenEnabled(false) }
                AlwaysListenService.stop(context)
                feedback = "Listening is off. Ilona will not listen for her name."
            }
        }
    )
    Text(
        when {
            !alwaysListen -> "Listening is off."
            runtime.serviceRunning && VoiceRuntime.heartbeatAgeMs() < 5_000 ->
                "Listening now — ${runtime.wakeEngine}."
            runtime.serviceRunning ->
                "Listening was on, but the listener has gone quiet. Turn it off and on again to restart it."
            else -> "Listening is on and starting…"
        }
    )
}

private fun packLine(
    pack: VoiceModelManager.Pack,
    status: VoiceModelManager.PackStatus
): String {
    val name = when (pack) {
        VoiceModelManager.Pack.TTS_KOKORO -> "Her speaking voice"
        VoiceModelManager.Pack.STT_PARAKEET -> "Her understanding (turning speech into words)"
        VoiceModelManager.Pack.WAKE_KWS -> "Hearing her name (“Hey Ilona”)"
    }
    val state = when (status.state) {
        VoiceModelManager.State.READY -> "ready."
        VoiceModelManager.State.ABSENT -> "not downloaded yet."
        VoiceModelManager.State.DOWNLOADING -> "downloading… ${status.percent}%"
        VoiceModelManager.State.VERIFYING -> "checking the download…"
        VoiceModelManager.State.EXTRACTING -> "getting it ready…"
        VoiceModelManager.State.FAILED -> "the download had a problem${status.message?.let { " ($it)" } ?: ""}. Tap Download to try again."
    }
    return "$name: $state"
}

private fun totalDownloadText(models: VoiceModelManager): String {
    val mb = models.missingBytes() / 1_000_000
    return if (mb >= 1000) "${mb / 1000}.${(mb % 1000) / 100} GB" else "$mb MB"
}
