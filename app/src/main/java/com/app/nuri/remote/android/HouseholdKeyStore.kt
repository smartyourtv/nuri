package com.app.nuri.remote.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.app.nuri.remote.HouseholdCrypto
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * On-device key custody (design §5.2). Each binding (real senior,
 * real caregiver, test senior, test caregiver) has its own tag and
 * its own files, so test mode can never touch the real binding's
 * keys (§11.3). Device private keys and the household key are
 * stored only as ciphertext under an Android-Keystore AES key that
 * never leaves the Keystore. Key material never enters DataStore,
 * logs, or crash reports — only "key missing/unreadable" facts do.
 *
 * The device-key record is stored as its three fields joined by
 * newlines (algorithm, public b64, private b64 — none can contain
 * a newline), encrypted as one blob.
 */
class HouseholdKeyStore(context: Context, private val tag: String) {

    private val dir: File = File(context.filesDir, "remote-keys/$tag").apply { mkdirs() }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(plain)
        return cipher.iv + ct
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    private fun writeFile(name: String, bytes: ByteArray) {
        File(dir, name).writeBytes(bytes)
    }

    private fun readFile(name: String): ByteArray? =
        File(dir, name).takeIf { it.exists() }?.readBytes()

    private fun encodeRecord(record: HouseholdCrypto.DeviceKeyRecord): ByteArray =
        listOf(record.algorithm, record.publicKeyB64, record.privateKeyB64)
            .joinToString("\n")
            .toByteArray(Charsets.UTF_8)

    private fun decodeRecord(plain: ByteArray): HouseholdCrypto.DeviceKeyRecord {
        val parts = String(plain, Charsets.UTF_8).split("\n")
        require(parts.size == 3) { "device key record is malformed" }
        return HouseholdCrypto.DeviceKeyRecord(parts[0], parts[1], parts[2])
    }

    /** This device's ECDH keypair for the binding, created on first use. */
    @Synchronized
    fun deviceKey(): HouseholdCrypto.DeviceKeyRecord {
        readFile("device_key")?.let { blob ->
            return try {
                decodeRecord(unwrap(blob))
            } catch (_: Exception) {
                // Corrupt key material: regenerate (pairing will be
                // redone by the caregiver flows; nothing else can
                // proceed with an unreadable key).
                createDeviceKey()
            }
        }
        return createDeviceKey()
    }

    private fun createDeviceKey(): HouseholdCrypto.DeviceKeyRecord {
        val record = HouseholdCrypto.generateDeviceKeyPair()
        writeFile("device_key", wrap(encodeRecord(record)))
        return record
    }

    /** Adopt an existing device key record (caregiver phone replacement, §4.3: the pending key becomes the binding key). */
    @Synchronized
    fun adoptDeviceKey(record: HouseholdCrypto.DeviceKeyRecord) {
        writeFile("device_key", wrap(encodeRecord(record)))
    }

    fun saveHouseholdKey(householdKey: ByteArray) {
        writeFile("household_key", wrap(householdKey))
    }

    fun householdKey(): ByteArray? = try {
        readFile("household_key")?.let { unwrap(it) }?.takeIf { it.size == 32 }
    } catch (_: Exception) {
        null
    }

    /** Clears this binding's keys only (test exit / unpair). Other tags are untouched. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val ALIAS = "ilona_household_master"

        /** Key-store tags: real and test bindings never share a tag (§11.3). */
        fun tagFor(householdId: String, role: String, test: Boolean): String =
            (if (test) "test-" else "real-") + role.lowercase() + "-" +
                householdId.take(12).replace(Regex("[^A-Za-z0-9-]"), "")
    }
}
