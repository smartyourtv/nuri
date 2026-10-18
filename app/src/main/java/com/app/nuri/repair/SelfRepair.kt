package com.app.nuri.repair

import com.app.nuri.update.PendingUpdate
import java.io.File

/**
 * Self-repair of recoverable STATE (standing rule, Shawn 2026-10-17):
 * repairs must be (1) conservative — verifiable and reversible only;
 * (2) verified — re-checked afterwards, success claimed only when the
 * check passes; (3) logged — every repair and every failed repair lands
 * in the crash/error report system, marked repaired / repair-failed.
 * Code bugs are NOT self-repairable: they surface as reports and are
 * fixed by update.
 *
 * This file is the pure half: the corruption-event hand-off from the
 * settings store, the file-preservation step, and the update-state
 * reconciler's decisions. The Android runner is repair/android/.
 */

/** A settings-corruption event captured by the DataStore corruption handler. */
data class SettingsCorruptionEvent(
    val exceptionClass: String,
    val exceptionMessage: String?,
    /** Name of the preserved copy of the damaged file, or null if preserving failed. */
    val preservedCopyName: String?
)

/**
 * Hand-off between the DataStore corruption handler (which fires inside
 * the store's own error path, at first damaged read) and the startup
 * repair runner (which probes, verifies, and writes the report entry).
 */
object SettingsRepairState {
    @Volatile private var event: SettingsCorruptionEvent? = null

    fun record(e: SettingsCorruptionEvent) { event = e }

    fun peek(): SettingsCorruptionEvent? = event

    /** Read and clear — the runner reports each corruption exactly once. */
    fun consume(): SettingsCorruptionEvent? {
        val e = event
        event = null
        return e
    }

    fun clearForTest() { event = null }
}

object SettingsCorruption {
    const val DATASTORE_DIR = "datastore"
    const val SETTINGS_FILE = "nuri_settings.preferences_pb"
    const val PRESERVED_DIR = "crash-reports/preserved"

    /**
     * Called from the settings store's corruption handler. Preserves a
     * copy of the damaged file inside the app (so the repair is
     * reversible and the evidence survives), records the event for the
     * startup repair runner, and returns the event. NEVER throws — it
     * runs inside DataStore's error handling.
     */
    fun onCorruption(filesDir: File?, throwable: Throwable): SettingsCorruptionEvent {
        var preserved: String? = null
        if (filesDir != null) {
            try {
                val damaged = File(File(filesDir, DATASTORE_DIR), SETTINGS_FILE)
                if (damaged.exists()) {
                    val destDir = File(filesDir, PRESERVED_DIR).apply { mkdirs() }
                    val dest = File(destDir, "nuri_settings-damaged-${System.currentTimeMillis()}.preferences_pb")
                    damaged.copyTo(dest, overwrite = false)
                    preserved = dest.name
                }
            } catch (_: Exception) {
                preserved = null // recorded as "copy failed" in the evidence
            }
        }
        // Unwrap to the root cause (the store's CorruptionException wraps
        // the underlying read failure); the root is the honest evidence.
        var cause: Throwable = throwable
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        val event = SettingsCorruptionEvent(
            exceptionClass = cause.javaClass.name,
            exceptionMessage = cause.message,
            preservedCopyName = preserved
        )
        SettingsRepairState.record(event)
        return event
    }
}

/** Snapshot of the self-update state the reconciler reasons about. */
data class UpdateStateSnapshot(
    val pending: PendingUpdate?,
    /** When the pending marker was written; 0 = unknown (pre-timestamp records). */
    val pendingWrittenAtMillis: Long,
    val installedVersionCode: Int,
    val updateJobScheduled: Boolean,
    /** Files sitting in the update download cache at startup. */
    val downloadFileNames: List<String>
)

/**
 * Startup reconciliation decisions for stale update state. Pure: the
 * Android runner executes each action, verifies it by re-reading, and
 * logs the outcome to the report store.
 */
object UpdateStateReconciler {

    /** A pending marker older than this, for a version that never installed, is stale. */
    const val STALE_PENDING_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    sealed interface Action {
        /** The pending marker describes an install that evidently never landed. */
        data class ClearStalePending(val pending: PendingUpdate) : Action
        /** An orphaned download: with no install pending it can only be a leftover. */
        data class DeleteStaleDownload(val fileName: String) : Action
        /** The nightly update job is not scheduled even after the startup attempt. */
        data object UpdateJobMissing : Action
    }

    fun decide(state: UpdateStateSnapshot, nowMillis: Long): List<Action> {
        val actions = ArrayList<Action>()
        val pending = state.pending
        if (pending != null &&
            state.installedVersionCode < pending.versionCode &&
            state.pendingWrittenAtMillis > 0 &&
            nowMillis - state.pendingWrittenAtMillis > STALE_PENDING_MILLIS
        ) {
            actions.add(Action.ClearStalePending(pending))
        }
        // Downloads are transient by design (the executor deletes on every
        // outcome); at startup with NO install pending, anything left is an
        // orphan from a run that died mid-download — possibly partial or
        // corrupt. Delete; the next check re-downloads and re-verifies, so
        // a damaged payload is never used. With an install pending, leave
        // the cache strictly alone (conservative).
        if (pending == null) {
            state.downloadFileNames.forEach { actions.add(Action.DeleteStaleDownload(it)) }
        }
        if (!state.updateJobScheduled) {
            actions.add(Action.UpdateJobMissing)
        }
        return actions
    }
}
