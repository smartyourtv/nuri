package com.app.nuri

import android.app.Application

/**
 * Nuri application class — scaffold.
 *
 * Mirrors Scout's ScoutApplication role (app-wide setup) but starts empty:
 * TTS/STT engine init, wake-word service, and the offline safety layer are
 * GATED features (see SCAFFOLD.md) and land here when approved.
 */
class NuriApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO(GATED): initialize shared Scout voice engine (tts/ + speech/).
        // TODO(GATED): start diagnostics collection.
    }
}
