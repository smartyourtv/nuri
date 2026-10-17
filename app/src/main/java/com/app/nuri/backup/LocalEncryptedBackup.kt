package com.app.nuri.backup

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Layer 3: local encrypted export (also the crypto core the other layers
 * reuse). AES-256-GCM, key derived with PBKDF2-HmacSHA256 (120k iterations)
 * from a caregiver-supplied passphrase + random salt. File format is text:
 * header line, then Base64 of salt(16) | iv(12) | ciphertext+tag.
 * Pure JVM crypto (java.security / javax.crypto) — self-testable offline.
 */
object BackupCrypto {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
    fun encrypt(plaintext: ByteArray, passphrase: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext)
        val blob = salt + iv + ct
        return "NURI-BACKUP-V1\n" + Base64.getEncoder().encodeToString(blob) + "\n"
    }
    fun decrypt(encoded: String, passphrase: String): ByteArray {
        val lines = encoded.trim().lines()
        require(lines.isNotEmpty() && lines[0] == "NURI-BACKUP-V1") { "Not a Nuri backup file." }
        val blob = Base64.getDecoder().decode(lines.drop(1).joinToString(""))
        require(blob.size > 28) { "Backup file is too short or damaged." }
        val salt = blob.copyOfRange(0, 16); val iv = blob.copyOfRange(16, 28); val ct = blob.copyOfRange(28, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct) // GCM tag failure throws — wrong passphrase / tampering is detected, never silently accepted.
    }
    /** Honest round-trip self-test used by Diagnostics. */
    fun selfTest(): BackupResult {
        return try {
            val probe = "nuri-self-test".toByteArray(Charsets.UTF_8)
            val enc = encrypt(probe, "self-test-passphrase-not-a-real-one")
            val dec = decrypt(enc, "self-test-passphrase-not-a-real-one")
            val wrongFails = try { decrypt(enc, "wrong-passphrase"); false } catch (e: Exception) { true }
            if (dec.contentEquals(probe) && wrongFails) BackupResult(true, "Encrypted backup self-test passed: save and restore match, and a wrong passphrase is rejected.")
            else BackupResult(false, "Encrypted backup self-test FAILED: round-trip or wrong-passphrase check did not behave as expected.")
        } catch (e: Exception) { BackupResult(false, "Encrypted backup self-test FAILED: ${e.message}") }
    }
}

class LocalEncryptedBackupManager(private val filesDir: File) : BackupManager {
    override val layerName = "Local encrypted export"
    override fun isAvailable(): Boolean = true
    override fun statusText(): String = "Ready. Exports an encrypted file on this phone (USB / SD / Bluetooth sharing is done by the caregiver from Files)."
    fun exportToFile(payload: String, passphrase: String): BackupResult {
        if (passphrase.length < 8) return BackupResult(false, "Passphrase is too short. Use at least 8 characters.")
        return try {
            val f = File(filesDir, "nuri-backup.nuri"); f.writeText(BackupCrypto.encrypt(payload.toByteArray(Charsets.UTF_8), passphrase))
            BackupResult(true, "Saved encrypted backup to ${f.name} (${f.length()} bytes).")
        } catch (e: Exception) { BackupResult(false, "Export failed: ${e.message}") }
    }
    fun importFromFile(passphrase: String): BackupResult {
        val f = File(filesDir, "nuri-backup.nuri")
        if (!f.exists()) return BackupResult(false, "No backup file found on this phone yet.")
        return try {
            val plain = BackupCrypto.decrypt(f.readText(), passphrase)
            val text = String(plain, Charsets.UTF_8)
            if (!text.startsWith("NURI-PAYLOAD-V1")) BackupResult(false, "Backup restored but its contents are not recognised — nothing was changed.")
            else BackupResult(true, "Backup file opened and checked OK (${text.length} characters). Applying settings is a caregiver step that lands with the real data store. TODO(real-data-store)")
        } catch (e: Exception) { BackupResult(false, "Could not open that backup. The passphrase may be wrong, or the file is damaged. Nothing was changed.") }
    }
}
