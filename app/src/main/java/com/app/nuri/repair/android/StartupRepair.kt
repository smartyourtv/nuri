package com.app.nuri.repair.android

import android.content.Context
import com.app.nuri.repair.SettingsRepairState
import com.app.nuri.repair.UpdateStateReconciler
import com.app.nuri.repair.UpdateStateSnapshot
import com.app.nuri.reports.RepairStatus
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.update.android.AndroidApkInspector
import com.app.nuri.update.android.UpdateRuntimeStore
import com.app.nuri.update.android.UpdateScheduler
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Startup self-repair runner (standing self-repair rule, Shawn
 * 2026-10-17). Runs on a background thread at every app start:
 *
 *  1. Settings: probe a read of the settings store. If the store's
 *     corruption handler recorded a damaged file on the way, the safe-
 *     defaults replacement IS the repair — a successful probe afterwards
 *     verifies it (entry: repaired). A probe that still fails is logged
 *     repair-failed. (Salvage honesty: individual keys inside a corrupt
 *     protobuf cannot be recovered through the public API; the damaged
 *     file itself is preserved inside the app, and every other store is
 *     untouched.)
 *  2. Update state: reconcile stale state via the pure
 *     [UpdateStateReconciler] — clear a proven-stale pending marker,
 *     delete orphaned downloads, note a missing nightly job. Each action
 *     is executed, verified by re-reading, and logged.
 *
 * Nothing here may block or crash startup: the whole run is guarded, and
 * its own failure becomes an error report instead.
 */
object StartupRepair {

    fun runAsync(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                repairSettings(app)
                reconcileUpdateState(app)
                repairVoiceListener(app)
            } catch (t: Throwable) {
                CrashReports.recordError("Startup repair", t)
            }
        }.start()
    }

    private fun repairSettings(app: Context) {
        val repo = SettingsRepository(app)
        // The probe read is what triggers the corruption handler if the
        // settings file is damaged — the handler preserves the file and
        // substitutes safe defaults, so a healthy return IS verification.
        val probeError = try {
            runBlocking { withTimeout(10_000) { repo.tier.first() } }
            null
        } catch (t: Throwable) {
            t
        }
        val event = SettingsRepairState.consume()
        when {
            event != null && probeError == null -> CrashReports.recordRepair(
                what = "Damaged settings file",
                evidence = "Ilona's saved settings could not be read " +
                    "(${event.exceptionClass}: ${event.exceptionMessage ?: "no message"}). " +
                    (event.preservedCopyName?.let {
                        "A copy of the damaged file was kept inside Ilona ($it), so nothing was thrown away. "
                    } ?: "Ilona tried to keep a copy of the damaged file, but that copy failed. ") +
                    "Settings were restarted from their safe standard values. " +
                    "Check after the repair: settings can be read again — passed. " +
                    "Any choices stored in the damaged file went back to their standard settings; " +
                    "everything stored elsewhere (including update records) was not touched.",
                status = RepairStatus.REPAIRED
            )
            event != null && probeError != null -> CrashReports.recordRepair(
                what = "Damaged settings file",
                evidence = "Ilona's saved settings could not be read " +
                    "(${event.exceptionClass}: ${event.exceptionMessage ?: "no message"}), and " +
                    "settings still could not be read after the repair attempt " +
                    "(${probeError.javaClass.name}: ${probeError.message ?: "no message"}). " +
                    "A fix for this needs an app update.",
                status = RepairStatus.REPAIR_FAILED
            )
            event == null && probeError != null ->
                CrashReports.recordError("Settings read at startup", probeError)
        }
    }

    private fun reconcileUpdateState(app: Context) {
        val store = UpdateRuntimeStore(app)
        val downloadsDir = File(app.cacheDir, "updates")
        val snapshot = UpdateStateSnapshot(
            pending = store.read(),
            pendingWrittenAtMillis = store.pendingWrittenAtMillis(),
            installedVersionCode = AndroidApkInspector(app).installedVersionCode(),
            updateJobScheduled = UpdateScheduler.isScheduled(app),
            downloadFileNames = downloadsDir.listFiles()
                ?.filter { it.isFile }?.map { it.name } ?: emptyList()
        )
        for (action in UpdateStateReconciler.decide(snapshot, System.currentTimeMillis())) {
            when (action) {
                is UpdateStateReconciler.Action.ClearStalePending -> {
                    store.clear()
                    val verified = store.read() == null
                    CrashReports.recordRepair(
                        what = "Update that never finished",
                        evidence = "Ilona had a note saying an update to version " +
                            "${action.pending.versionName} (code ${action.pending.versionCode}) was " +
                            "on its way, but that version never arrived and the note was more " +
                            "than a week old. The stale note was cleared. " +
                            "Check after the repair: " +
                            if (verified) "the note is gone — passed." else "the note is still there — failed.",
                        status = if (verified) RepairStatus.REPAIRED else RepairStatus.REPAIR_FAILED
                    )
                }
                is UpdateStateReconciler.Action.DeleteStaleDownload -> {
                    val file = File(downloadsDir, action.fileName)
                    val gone = try { file.delete() || !file.exists() } catch (_: Exception) { !file.exists() }
                    CrashReports.recordRepair(
                        what = "Leftover update download",
                        evidence = "A downloaded update file (${action.fileName}) was left over " +
                            "from an earlier check that did not finish. It was deleted without " +
                            "being used — a partly-downloaded or damaged file is never installed. " +
                            "The next update check will download a fresh copy and verify it before " +
                            "anything is installed. Check after the repair: " +
                            if (gone) "the file is gone — passed." else "the file is still there — failed.",
                        status = if (gone) RepairStatus.REPAIRED else RepairStatus.REPAIR_FAILED
                    )
                }
                UpdateStateReconciler.Action.UpdateJobMissing -> {
                    // v0.3.1: the vc3 runner reported "could not fix it"
                    // without ever attempting a fix. Now it actually tries:
                    // run the (defensive, laddered) scheduler, then verify
                    // by re-reading pending jobs — the self-repair rule.
                    val attempted = runCatching { UpdateScheduler.schedule(app) }.getOrDefault(false)
                    val scheduledNow = attempted && UpdateScheduler.isScheduled(app)
                    if (scheduledNow) {
                        store.clearRepairFailureSignature()
                        CrashReports.recordRepair(
                            what = "Nightly update check not scheduled",
                            evidence = "The nightly update check was not scheduled when Ilona started. " +
                                "Ilona scheduled it again during this start. " +
                                "Check after the repair: the nightly check is scheduled — passed.",
                            status = RepairStatus.REPAIRED
                        )
                    } else {
                        // Dedupe: the same known failure must not produce a
                        // new report on every launch. One report per app
                        // version; the scheduler's own failure report
                        // carries the step-by-step evidence.
                        val signature = "job-missing-vc${AndroidApkInspector(app).installedVersionCode()}"
                        if (store.repairFailureSignature() != signature) {
                            store.setRepairFailureSignature(signature)
                            CrashReports.recordRepair(
                                what = "Nightly update check not scheduled",
                                evidence = "The nightly update check was not scheduled when Ilona started, " +
                                    "and scheduling it again during this start did not succeed — the " +
                                    "separate scheduling report from this start carries the technical " +
                                    "evidence. Update checks will run when Ilona is opened instead. " +
                                    "This failure is reported once for this app version, not on every start.",
                                status = RepairStatus.REPAIR_FAILED
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Voice listener repair (v0.4.0): if listening is switched on and
     * the microphone is allowed, but the listener's heartbeat is stale
     * (service dead — Android kills background services), restart it
     * and VERIFY via a fresh heartbeat before claiming the repair.
     * Starting a microphone foreground service can be refused while
     * the app is fully in the background on newer Android versions;
     * that refusal is logged once per version, and the listener then
     * starts when Ilona is next opened (the shell does the same
     * check). Never crashes startup.
     */
    private fun repairVoiceListener(app: Context) {
        val repo = SettingsRepository(app)
        val enabled = runCatching {
            runBlocking { withTimeout(5_000) { repo.personToggles.first() } }.alwaysListenEnabled ?: false
        }.getOrDefault(false)
        if (!enabled) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val age = com.app.nuri.speech.VoiceRuntime.heartbeatAgeMs()
        if (com.app.nuri.speech.VoiceRuntime.snapshot().serviceRunning && age < 30_000) return
        val started = runCatching {
            com.app.nuri.speech.android.AlwaysListenService.start(app)
            true
        }.getOrDefault(false)
        // Verify: a live listener heartbeats every ~80 ms frame.
        var verified = false
        if (started) {
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                if (com.app.nuri.speech.VoiceRuntime.heartbeatAgeMs() < 2_000) { verified = true; break }
                try { Thread.sleep(150) } catch (_: InterruptedException) { break }
            }
        }
        if (verified) {
            CrashReports.recordRepair(
                what = "Listening service restart",
                evidence = "Listening for “Hey Ilona” is switched on, but the listener was not " +
                    "running when Ilona started. It was started again during this start. " +
                    "Check after the repair: the listener's heartbeat is fresh — passed.",
                status = RepairStatus.REPAIRED
            )
        } else {
            val store = UpdateRuntimeStore(app)
            val signature = "listener-dead-vc${AndroidApkInspector(app).installedVersionCode()}"
            if (store.repairFailureSignature() != signature) {
                store.setRepairFailureSignature(signature)
                CrashReports.recordRepair(
                    what = "Listening service restart",
                    evidence = "Listening for “Hey Ilona” is switched on, but the listener was not " +
                        "running when Ilona started, and restarting it during this start did not " +
                        "produce a heartbeat within 3 seconds. It will start when Ilona is next " +
                        "opened to the home screen. This failure is reported once for this app " +
                        "version, not on every start.",
                    status = RepairStatus.REPAIR_FAILED
                )
            }
        }
    }
}
