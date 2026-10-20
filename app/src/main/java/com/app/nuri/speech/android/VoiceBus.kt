package com.app.nuri.speech.android

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * VoiceBus — final utterances from the listen service to the UI.
 * MainActivity collects [utterances] and routes each one through
 * EXACTLY the same seam a typed message takes (LL4: a transcription
 * is just another way of typing — same matcher, same help ladder,
 * same history hygiene).
 */
object VoiceBus {
    private val _utterances = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val utterances: SharedFlow<String> = _utterances

    fun publish(text: String) {
        _utterances.tryEmit(text)
    }
}
