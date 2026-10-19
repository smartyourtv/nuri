package com.app.nuri.speech

import com.app.nuri.config.NuriSettings

/** State for VAD-gated Always listen ("Hey Nuri"). Service wiring is limited
 *  by the scaffold VoicePipeline contract: no foreground service / mic loop
 *  exists yet, so [running] is honest state, never faked. */
data class AlwaysListenState(
    val enabledBySettings: Boolean = true,
    val running: Boolean = false,
    val gateOpen: Boolean = false,
    val lastHeardText: String = "Not built yet — the listening service arrives with on-device testing.",
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
             " Listening service itself: not built yet (needs on-device testing)."
}
