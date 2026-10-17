package com.app.nuri.viewmodel

import androidx.lifecycle.ViewModel

/**
 * Home ViewModel — scaffold placeholder.
 *
 * Will own the conversation state machine (speech/VoiceState) when the
 * gated voice work lands. Mirrors Scout's ViewModel layering: UI collects
 * state, repository owns storage, speech/tts packages own the pipeline.
 */
class HomeViewModel : ViewModel() {
    // TODO(GATED): conversation state, check-in state, routine anchors.
}
