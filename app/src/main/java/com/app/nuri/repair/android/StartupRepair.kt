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
                evidence = "Nuri's saved settings could not be read " +
                    "(${event.exceptionClass}: ${event.exceptionMessage ?: "no message"}). " +
                    (event.preservedCopyName?.let {
                        "A copy of the damaged file was kept inside Nuri ($it), so nothing was thrown away. "
                    } ?: "Nuri tried to keep a copy of the damaged file, but that copy failed. ") +
                    "Settings were restarted from their safe standard values. " +
                    "Check after the repair: settings can be read again — passed. " +
                    "Any choices stored in the damaged file went back to their standard settings; " +
                    "everything stored elsewhere (including update records) was not touched.",
                status = RepairStatus.REPAIRED
            )
            event != null && probeError != null -> CrashReports.recordRepair(
                what = "Damaged settings file",
                evidence = "Nuri's saved settings could not be read " +
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
                        evidence = "Nuri had a note saying an update to version " +
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
                UpdateStateReconciler.Action.UpdateJobMissing -> CrashReports.recordRepair(
                    what = "Nightly update check not scheduled",
                    evidence = "Nuri tried to schedule its nightly update check at startup, " +
                        "but the check is still not scheduled afterwards. Automatic update " +
                        "checks will not run until this is fixed by an app update.",
                    status = RepairStatus.REPAIR_FAILED
                )
            }
        }
    }
}
