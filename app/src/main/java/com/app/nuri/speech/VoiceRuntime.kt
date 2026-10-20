package com.app.nuri.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VoiceRuntime — the LIVE voice state, written by the actual owners
 * (the always-listen service, the TTS engine, the model manager)
 * and read by the voice health card.
 *
 * LL9's law lives here: the card renders THIS object, never a
 * cached settings flag, and the heartbeat timestamp lets the card
 * say "the listener has gone quiet" instead of repeating a stale
 * "listening". A state claim that can't be verified reads as
 * unknown — never as healthy.
 */
data class VoiceRuntimeState(
    val serviceRunning: Boolean = false,
    /** Last capture-loop heartbeat (epoch ms); 0 = never heard from. */
    val heartbeatMs: Long = 0L,
    val micFrames: Long = 0L,
    /** True when frames are actually changing (a live mic, not a stuck buffer). */
    val micLive: Boolean = false,
    val vadFloor: Float = 0f,
    val vadLastRms: Float = 0f,
    val speechWindows: Long = 0L,
    val wakeCount: Int = 0,
    val lastWakeMs: Long = 0L,
    val lastHeard: String? = null,
    val lastTranscription: String? = null,
    val sessionOpen: Boolean = false,
    val wakeEngine: String = "not started",
    val sttEngine: String = "not started",
    val ttsEngine: String = "not started",
    val speakingNow: Boolean = false,
    val routedDevice: String? = null,
    val routeVerified: Boolean = false,
    val lastFailure: String? = null,
    val failureCount: Int = 0
)

object VoiceRuntime {

    private val _state = MutableStateFlow(VoiceRuntimeState())
    val state: StateFlow<VoiceRuntimeState> = _state

    fun snapshot(): VoiceRuntimeState = _state.value

    private fun update(transform: (VoiceRuntimeState) -> VoiceRuntimeState) {
        _state.value = transform(_state.value)
    }

    fun serviceStarted() = update {
        it.copy(serviceRunning = true, heartbeatMs = System.currentTimeMillis())
    }

    fun serviceStopped(reason: String? = null) = update {
        it.copy(serviceRunning = false, sessionOpen = false, wakeEngine = reason ?: it.wakeEngine)
    }

    /** Called by the capture loop for EVERY 80 ms window. */
    fun heartbeat(frames: Long, floor: Float, rms: Float, micLive: Boolean, speechWindows: Long) = update {
        it.copy(
            heartbeatMs = System.currentTimeMillis(),
            micFrames = frames,
            vadFloor = floor,
            vadLastRms = rms,
            micLive = micLive,
            speechWindows = speechWindows
        )
    }

    fun wakeHeard() = update {
        it.copy(wakeCount = it.wakeCount + 1, lastWakeMs = System.currentTimeMillis(), sessionOpen = true)
    }

    fun session(open: Boolean) = update { it.copy(sessionOpen = open) }
    fun lastHeard(text: String) = update { it.copy(lastHeard = text) }
    fun transcribed(text: String) = update { it.copy(lastTranscription = text) }
    fun setWakeEngine(label: String) = update { it.copy(wakeEngine = label) }
    fun setSttEngine(label: String) = update { it.copy(sttEngine = label) }
    fun setTtsEngine(label: String) = update { it.copy(ttsEngine = label) }
    fun setSpeaking(speaking: Boolean) = update { it.copy(speakingNow = speaking) }

    fun setRoutedDevice(label: String, verified: Boolean) = update {
        it.copy(routedDevice = label, routeVerified = verified)
    }

    fun noteFailure(what: String) = update {
        it.copy(lastFailure = what, failureCount = it.failureCount + 1)
    }

    /** Heartbeat age in ms; Long.MAX_VALUE when there has never been one. */
    fun heartbeatAgeMs(nowMs: Long = System.currentTimeMillis()): Long {
        val hb = _state.value.heartbeatMs
        return if (hb == 0L) Long.MAX_VALUE else nowMs - hb
    }
}
