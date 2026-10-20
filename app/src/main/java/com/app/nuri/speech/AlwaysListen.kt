package com.app.nuri.speech

import com.app.nuri.config.NuriSettings

/**
 * State for VAD-gated Always listen ("Hey Ilona").
 *
 * v0.4.0 note: the live listener is speech.android.AlwaysListenService
 * and its live state lives in speech.VoiceRuntime (that is what the
 * voice health card reads). This scaffold state/controller pair is
 * retained for the VAD-gate unit tests; nothing user-visible reads
 * it anymore.
 */
data class AlwaysListenState(
    val enabledBySettings: Boolean = true,
    val running: Boolean = false,
    val gateOpen: Boolean = false,
    val lastHeardText: String = "Live listening state is on the voice health card (Health check tab).",
    val wakePhrase: String = NuriSettings.WAKE_PHRASE
)

class AlwaysListenController(private val vad: EnergyVad = EnergyVad()) {
    private var enabled = true
    private var gateOpen = false
    /** Caregiver (or senior in Independent) can disable Always listen. */
    fun setEnabled(value: Boolean) { enabled = value; if (!value) gateOpen = false }
    fun isEnabled(): Boolean = enabled
    /** Feed one 80ms window; returns true only if wake-word spotting should run. */
    fun onWindow(samples: FloatArray): Boolean {
        if (!enabled) return false
        gateOpen = vad.acceptWindow(samples); return gateOpen
    }
    fun state(runningService: Boolean = false): AlwaysListenState = AlwaysListenState(
        enabledBySettings = enabled, running = runningService && enabled, gateOpen = gateOpen)
    fun diagnosticsText(): String =
        if (!enabled) "Always listen is OFF. Ilona only listens when you tap and talk."
        else "Always listen is set ON, waiting for '${NuriSettings.WAKE_PHRASE}'. " + vad.diagnosticsText() +
             " Live listener state is on the voice health card."
}
