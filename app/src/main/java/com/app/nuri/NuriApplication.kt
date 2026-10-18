package com.app.nuri

import android.app.Application
import android.content.Context
import com.app.nuri.repair.android.StartupRepair
import com.app.nuri.reports.android.CrashReports

/**
 * Nuri application class — scaffold.
 *
 * Mirrors Scout's ScoutApplication role (app-wide setup) but starts empty:
 * TTS/STT engine init, wake-word service, and the offline safety layer are
 * GATED features (see SCAFFOLD.md) and land here when approved.
 */
class NuriApplication : Application() {

    /**
     * The crash-report handler installs HERE — in attachBaseContext,
     * before any onCreate work — because the failure this build exists to
     * capture happens during startup (standing crash-report rule,
     * 2026-10-17). A crash anywhere after this point writes its report
     * file to Downloads synchronously on the crashing thread.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReports.install(base)
        CrashReports.breadcrumbs.mark("Nuri started")
    }

    override fun onCreate() {
        super.onCreate()
        CrashReports.breadcrumbs.mark("app setup started")
        // Self-update (brief §10): schedule the nightly background check.
        // Idempotent — UpdateScheduler keeps the existing job if present.
        // Guarded (crash investigation 2026-10-17): scheduling is background
        // bookkeeping — it must NEVER be able to crash app launch, so any
        // failure is recorded as an error report and startup continues.
        runCatching { com.app.nuri.update.android.UpdateScheduler.schedule(this) }
            .onFailure { e ->
                CrashReports.recordError("Update schedule at startup", e)
            }
        CrashReports.breadcrumbs.mark("update check scheduled")
        // Self-repair (standing rule, 2026-10-17): probe the settings
        // store and reconcile stale update state, on a background thread;
        // every repair is verified and logged to the report store.
        StartupRepair.runAsync(this)
        CrashReports.breadcrumbs.mark("startup repair running")
        // TODO(GATED): initialize shared Scout voice engine (tts/ + speech/).
        // TODO(GATED): start diagnostics collection.
        CrashReports.breadcrumbs.mark("app setup finished")
    }
}
