package com.app.nuri.backup

/**
 * Snapshot backups (Shawn, 2026-10-17): dated, restorable state snapshots
 * — weekly automatically + on demand, keep the last 3, same encryption as
 * backups (Android Keystore via EncryptedFile on the Android side).
 *
 * This object is the pure rotation/scheduling logic; the Android store
 * (backup/android/SnapshotStore.kt) does the file work. Snapshots capture
 * Nuri's state (settings + records). The known-good settings values in a
 * snapshot are also what Unstuck's display-drift helpers restore toward
 * (catalog B6 note).
 */
data class SnapshotInfo(val epochMs: Long, val label: String, val sizeBytes: Long)

object SnapshotRotation {
    const val KEEP: Int = 3
    const val WEEKLY_INTERVAL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Weekly snapshot due? (No snapshots at all counts as due.) */
    fun weeklyDue(snapshots: List<SnapshotInfo>, nowMs: Long): Boolean {
        val newest = snapshots.maxByOrNull { it.epochMs } ?: return true
        return nowMs - newest.epochMs >= WEEKLY_INTERVAL_MS
    }

    /** Files to delete so only the newest [KEEP] remain. */
    fun toPrune(snapshots: List<SnapshotInfo>): List<SnapshotInfo> =
        snapshots.sortedByDescending { it.epochMs }.drop(KEEP)

    fun describe(snapshots: List<SnapshotInfo>): String = when {
        snapshots.isEmpty() -> "No snapshots yet. Snapshots are dated copies of Ilona's settings and records, kept in case something needs putting back."
        else -> {
            val newest = snapshots.maxByOrNull { it.epochMs }!!
            "Keeping ${snapshots.size} of $KEEP snapshots. Snapshots are encrypted on this phone; restoring one is a caregiver-approved step."
        }
    }
}
