package com.app.nuri.backup.android

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.app.nuri.backup.SnapshotInfo
import com.app.nuri.backup.SnapshotRotation
import com.app.nuri.reports.android.CrashReports
import java.io.File

/**
 * On-device snapshot store (sweep #28): dated state snapshots in
 * app-private storage, encrypted with an Android-Keystore master key
 * (AES-256-GCM via EncryptedFile — the same crypto family as the local
 * backup layer). Rotation keeps the newest [SnapshotRotation.KEEP].
 * Restoring is a caregiver-approved step (CaregiverMediatedRecovery);
 * this store only writes, lists and verifies snapshots.
 */
object SnapshotStore {
    private fun dir(context: Context): File = File(context.filesDir, "snapshots").apply { mkdirs() }

    private fun masterKey(context: Context): MasterKey =
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    fun take(context: Context, label: String, payload: String, nowMs: Long): SnapshotInfo? {
        return try {
            val file = File(dir(context), "$nowMs.snap")
            val encrypted = EncryptedFile.Builder(
                context, file, masterKey(context),
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            encrypted.openFileOutput().use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            prune(context)
            SnapshotInfo(nowMs, label, file.length())
        } catch (e: Exception) {
            CrashReports.recordError("Snapshot write", e); null
        }
    }

    fun list(context: Context): List<SnapshotInfo> = runCatching {
        dir(context).listFiles { f -> f.name.endsWith(".snap") }.orEmpty().mapNotNull { f ->
            val epoch = f.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
            SnapshotInfo(epoch, "Snapshot", f.length())
        }.sortedByDescending { it.epochMs }
    }.getOrDefault(emptyList())

    /** Verify a snapshot decrypts intact (self-repair condition: verify before claiming). */
    fun verify(context: Context, info: SnapshotInfo): Boolean = runCatching {
        val file = File(dir(context), "${info.epochMs}.snap")
        val encrypted = EncryptedFile.Builder(
            context, file, masterKey(context),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        encrypted.openFileInput().use { it.readBytes().isNotEmpty() }
    }.getOrDefault(false)

    fun prune(context: Context) {
        runCatching {
            for (stale in SnapshotRotation.toPrune(list(context))) {
                File(dir(context), "${stale.epochMs}.snap").delete()
            }
        }
    }
}
