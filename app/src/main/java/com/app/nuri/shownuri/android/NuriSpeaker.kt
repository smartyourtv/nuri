package com.app.nuri.shownuri.android

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The INTERIM speaking voice for Show Nuri (v0.3.2): Android's
 * built-in TextToSpeech — platform API, no new dependencies, no
 * downloads. This is deliberately NOT Nuri's designed voice (the
 * deferred Kokoro stack); it exists so the Show Nuri screen's
 * promise — "I'll read it aloud" — is literally true in this build.
 * Scope: Show Nuri results only. Wider TTS coverage is the voice
 * round, not this one.
 *
 * Senior profile: speech rate 0.85 (slower pace, clear enunciation).
 * If the engine is missing or fails to start, every call is a safe
 * no-op — the text stays on screen and nothing pretends to speak.
 * Utterances requested before the engine is ready are held and
 * spoken when it is (results usually arrive after init; a photo
 * answered in the first second must still be read).
 */
class NuriSpeaker(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: List<String>? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.language = Locale.US }
                runCatching { tts?.setSpeechRate(SPEECH_RATE) }
                ready = true
                pending?.let { speakNow(it) }
                pending = null
            }
        }
    }

    fun speak(utterances: List<String>) {
        if (utterances.isEmpty()) return
        if (!ready) {
            pending = utterances
            return
        }
        speakNow(utterances)
    }

    private fun speakNow(utterances: List<String>) {
        val engine = tts ?: return
        utterances.forEachIndexed { index, text ->
            runCatching {
                engine.speak(text, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "nuri-shownuri-$index")
            }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        pending = null
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        pending = null
    }

    companion object {
        const val SPEECH_RATE: Float = 0.85f
    }
}
