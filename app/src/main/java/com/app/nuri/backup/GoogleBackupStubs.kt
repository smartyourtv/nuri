package com.app.nuri.backup

/** Layer 1: Drive hidden app-data folder. Layer 2: Gmail encrypted snapshot.
 *  Both need Google sign-in wiring that is NOT built — honest not-connected
 *  implementations. They must never report success. TODO(GATED-GOOGLE). */
class DriveAppDataBackup : BackupManager {
    override val layerName = "Google Drive backup (hidden app folder)"
    override fun isAvailable(): Boolean = false
    override fun statusText(): String = "Not connected yet. When set up, Nuri will quietly save an encrypted copy to the senior's own Google Drive each day. TODO(GATED-GOOGLE): sign-in and Drive app-data wiring."
    fun backupNow(): BackupResult = BackupResult(false, "Not connected yet — no backup was sent anywhere.")
}
class GmailSnapshotBackup : BackupManager {
    override val layerName = "Email snapshot backup"
    override fun isAvailable(): Boolean = false
    override fun statusText(): String = "Not connected yet. When set up, an encrypted snapshot can be emailed to the senior and the caregiver as a second copy. TODO(GATED-GOOGLE): Gmail wiring."
    fun backupNow(): BackupResult = BackupResult(false, "Not connected yet — no email was sent.")
}
