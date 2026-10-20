package com.app.nuri.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.nuri.speech.MicStreamHealth
import com.app.nuri.speech.VoiceRuntime
import com.app.nuri.tts.VoiceProfiles
import com.app.nuri.tts.android.IlonaSpeaker
import com.app.nuri.tts.android.VoiceModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The voice health card (v0.4.0) — the standing diagnostics
 * directive applied to voice: every line is read from the LIVE
 * owners (VoiceRuntime, written by the listener service and the
 * speech engine for every 80 ms window; the model manager's own
 * status), never from a saved settings flag. A heartbeat that has
 * gone stale says so, out loud, instead of repeating "all good".
 *
 * The one-tap self-test plays her samples — a normal line, each
 * emotion sound, a chuckle, a full laugh, a breath — in the
 * SELECTED voice, for the owner to judge BY EAR (acceptance is by
 * ear only; this card never claims the sounds are good).
 */
@Composable
fun VoiceHealthCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember(context) { IlonaSpeaker.shared(context.applicationContext) }
    val runtime by VoiceRuntime.state.collectAsState()
    val packStatuses by speaker.models.statuses.collectAsState()
    val spokeWith by speaker.lastSpokeWith.collectAsState()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ilona's voice and listening", style = MaterialTheme.typography.titleMedium)

            // Listener — from the service's own heartbeat.
            val heartbeatAge = VoiceRuntime.heartbeatAgeMs()
            Text(
                when {
                    !runtime.serviceRunning -> "Listening: off. Turn it on in Settings → Ilona's voice."
                    heartbeatAge < 5_000 -> "Listening: on — ${runtime.wakeEngine}."
                    else -> "Listening: the listener has gone quiet (last sign of life ${heartbeatAge / 1000} seconds ago). Turn listening off and on again in Settings."
                }
            )
            if (runtime.serviceRunning) {
                Text(
                    if (runtime.micLive) "Microphone: working — it can hear the room."
                    else MicStreamHealth.silenceMessage(runtime.micSilenced)
                )
                Text("Microphone in use: ${runtime.micInputLabel ?: "not known yet"}.")
                runtime.micFixLabel?.let { Text(it) }
                Text("Room sound: ${roomSoundText(runtime.vadFloor)}. Sound moments heard: ${runtime.speechWindows}.")
                Text("“Hey Ilona” heard ${runtime.wakeCount} time${if (runtime.wakeCount == 1) "" else "s"} since listening started.")
                runtime.lastHeard?.let { Text("Last thing heard: “$it”") }
                runtime.lastTranscription?.let { Text("Last thing understood: “$it”") }
            }
            Text("Understanding: ${runtime.sttEngine}.")
            Text("Her voice: ${voiceStateText(speaker, packStatuses, spokeWith)}")
            Text(
                "Sound comes out through: " + (runtime.routedDevice?.let {
                    if (runtime.routeVerified) "$it (checked while she was speaking)." else "$it (not checked yet)."
                } ?: "not checked yet — play the voice test below to check.")
            )
            // June's bank, honestly.
            Text(
                if (speaker.bankAvailable())
                    "June's laugh and feeling sounds: ${speaker.bankEntryCount()} sounds ready, made in her voice."
                else
                    "June's own laugh sounds are not in this build yet — her chuckles use the standard in-voice method for now."
            )
            if (runtime.failureCount > 0) {
                Text("Voice problems seen: ${runtime.failureCount}. Most recent: ${runtime.lastFailure ?: "—"}")
                Text("Details are saved in Settings → Crash & error reports.")
            }

            Button(
                onClick = { scope.launch(Dispatchers.Default) { speaker.runSelfTest() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Hear Ilona's voice — play the voice test") }
            if (runtime.speakingNow) {
                OutlinedButton(
                    onClick = { speaker.stop() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stop talking") }
            }
        }
    }
}

private fun roomSoundText(floor: Float): String = when {
    floor <= 0f -> "not measured yet"
    floor < 0.006f -> "a quiet room"
    floor < 0.02f -> "a normal room"
    else -> "a noisy room"
}

private fun voiceStateText(
    speaker: IlonaSpeaker,
    packStatuses: Map<VoiceModelManager.Pack, VoiceModelManager.PackStatus>,
    spokeWith: IlonaSpeaker.SpokeWith
): String {
    val tts = packStatuses[VoiceModelManager.Pack.TTS_KOKORO] ?: speaker.models.status(VoiceModelManager.Pack.TTS_KOKORO)
    val voiceName = runCatching {
        speaker.currentVoice().displayName.substringBefore(" —")
    }.getOrDefault("June")
    return when {
        speaker.engine.isReady ->
            "her own voice is ready — $voiceName. " + when (spokeWith) {
                IlonaSpeaker.SpokeWith.KOKORO -> "The last thing she said used it."
                IlonaSpeaker.SpokeWith.PLATFORM -> "The last thing she said used the phone's voice (her voice wasn't ready then)."
                IlonaSpeaker.SpokeWith.NONE -> "She hasn't spoken yet since the app opened."
            }
        tts.state == VoiceModelManager.State.DOWNLOADING -> "downloading (${tts.percent}%). Until it's ready, the phone's built-in voice speaks for her — the Settings screen says so too."
        tts.state == VoiceModelManager.State.FAILED -> "the download had a problem. Until it's fixed, the phone's built-in voice speaks for her."
        else -> "not downloaded yet. Until it is, the phone's built-in voice speaks for her — never passed off as her own voice."
    }
}
