package com.app.nuri.backup

/** Backup/restore domain (brief SS8). Four layers; restore is a caregiver
 *  flow, the senior does nothing. Nuri NEVER holds the password: the
 *  passphrase is supplied by the caregiver at export/import time only and is
 *  never stored, spoken, or displayed by this code. */
interface BackupManager {
    val layerName: String
    fun isAvailable(): Boolean
    fun statusText(): String
}
data class BackupResult(val ok: Boolean, val detail: String)
