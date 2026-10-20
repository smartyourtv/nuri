package com.app.nuri.remote

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Payload encryption (design §5.1): AES-256-GCM under the 32-byte
 * household key. Wire format is a single base64 string:
 * iv(12) | ciphertext+tag. Same primitives as the backup crypto
 * (backup/LocalEncryptedBackup.kt) — one crypto style across the app.
 *
 * Pure JVM: unit-tested directly, including tamper failure — a GCM
 * tag mismatch throws, never silently accepts.
 */
object PayloadCipher {

    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun newHouseholdKey(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun encrypt(householdKey: ByteArray, plaintext: ByteArray, random: SecureRandom = SecureRandom()): String {
        require(householdKey.size == KEY_BYTES) { "household key must be $KEY_BYTES bytes" }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(householdKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(householdKey: ByteArray, encoded: String): ByteArray {
        require(householdKey.size == KEY_BYTES) { "household key must be $KEY_BYTES bytes" }
        val blob = Base64.getDecoder().decode(encoded)
        require(blob.size > IV_BYTES) { "payload too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(householdKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct) // throws AEADBadTagException on tamper / wrong key
    }

    fun encryptText(householdKey: ByteArray, plaintext: String): String =
        encrypt(householdKey, plaintext.toByteArray(Charsets.UTF_8))

    fun decryptText(householdKey: ByteArray, encoded: String): String =
        decrypt(householdKey, encoded).toString(Charsets.UTF_8)
}
