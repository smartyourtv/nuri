package com.app.nuri.shownuri.android

import android.content.Context
import com.app.nuri.tts.android.IlonaSpeaker

/**
 * Show Ilona's read-aloud voice.
 *
 * v0.3.2 shipped this as the phone's platform voice, honestly
 * labeled as the interim. v0.4.0 routes it through the app-wide
 * speaker seam (IlonaSpeaker): when her real voice (Kokoro, June by
 * default) is downloaded and ready, Show Ilona reads in the
 * SELECTED voice; until then the phone's built-in voice speaks and
 * the Settings → Voice card says exactly that. The class and its
 * API stay so ShowNuriScreen is untouched.
 */
class NuriSpeaker(context: Context) {

    private val speaker = IlonaSpeaker.shared(context)

    /**
     * Speak [utterances] in order, replacing anything currently
     * being read. The seam serializes speech; the utterances queue
     * in order on its executor.
     */
    fun speak(utterances: List<String>) {
        val lines = utterances.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        speaker.stop()
        lines.forEach { speaker.speakAsync(it) }
    }

    fun stop() {
        speaker.stop()
    }

    /** This screen going away must never leave speech running. */
    fun shutdown() {
        speaker.stop()
    }

    companion object {
        /** Kept for API compatibility with v0.3.2 callers; the seam now owns rates. */
        const val SPEECH_RATE: Float = 0.85f
    }
}
