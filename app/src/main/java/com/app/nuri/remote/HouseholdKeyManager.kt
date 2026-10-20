package com.app.nuri.remote

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Household key establishment (design §5.2): the household key (HK)
 * never crosses the wire in the clear. Each device holds an ECDH key
 * pair; HK is wrapped to a recipient's public key with an ephemeral
 * ECDH exchange (shared secret → SHA-256 → AES-256-GCM wrap key).
 *
 * Algorithm: X25519 where the platform offers it (the design's
 * named curve), P-256 as the universal fallback — the algorithm is
 * recorded in the wrap blob and in the device-key record, and both
 * directions interoperate because the blob says which was used.
 * Private keys never leave the device; at rest they are stored
 * encrypted under an Android Keystore key (see
 * remote/android/HouseholdKeyStore.kt).
 *
 * This file is the pure crypto core (JVM-tested). Storage is the
 * Android layer's job.
 */
object HouseholdCrypto {

    const val ALGO_X25519 = "X25519"
    const val ALGO_P256 = "P256"

    /** A device key pair, serializable for encrypted-at-rest storage. */
    @Serializable
    data class DeviceKeyRecord(
        val algorithm: String,
        /** X.509-encoded public key, base64. Safe to post in a pairing claim. */
        val publicKeyB64: String,
        /** PKCS#8-encoded private key, base64. NEVER posted anywhere;
         *  stored on-device only, encrypted at rest. */
        val privateKeyB64: String,
    )

    @Serializable
    data class WrapBlob(
        val v: Int = 1,
        val algorithm: String,
        /** Ephemeral public key (X.509, base64). */
        val ephemeralPublicKeyB64: String,
        /** PayloadCipher blob: iv|ciphertext of the household key. */
        val wrappedKeyB64: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** X25519 first (design §5.2); P-256 when the platform lacks it. */
    fun preferredAlgorithm(): String = try {
        KeyPairGenerator.getInstance("X25519")
        ALGO_X25519
    } catch (_: Exception) {
        ALGO_P256
    }

    fun generateDeviceKeyPair(algorithm: String = preferredAlgorithm()): DeviceKeyRecord {
        val pair = generateKeyPair(algorithm)
        return DeviceKeyRecord(
            algorithm = algorithm,
            publicKeyB64 = Base64.getEncoder().encodeToString(pair.public.encoded),
            privateKeyB64 = Base64.getEncoder().encodeToString(pair.private.encoded),
        )
    }

    private fun generateKeyPair(algorithm: String): KeyPair = when (algorithm) {
        ALGO_X25519 -> KeyPairGenerator.getInstance("X25519").generateKeyPair()
        ALGO_P256 -> KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        else -> throw IllegalArgumentException("unknown key algorithm: $algorithm")
    }

    private fun publicKey(algorithm: String, b64: String): PublicKey {
        val bytes = Base64.getDecoder().decode(b64)
        return when (algorithm) {
            ALGO_X25519 -> KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(bytes))
            ALGO_P256 -> KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            else -> throw IllegalArgumentException("unknown key algorithm: $algorithm")
        }
    }

    private fun privateKey(algorithm: String, b64: String): PrivateKey {
        val bytes = Base64.getDecoder().decode(b64)
        return when (algorithm) {
            ALGO_X25519 -> KeyFactory.getInstance("X25519").generatePrivate(PKCS8EncodedKeySpec(bytes))
            ALGO_P256 -> KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
            else -> throw IllegalArgumentException("unknown key algorithm: $algorithm")
        }
    }

    private fun agreement(algorithm: String): KeyAgreement = when (algorithm) {
        ALGO_X25519 -> KeyAgreement.getInstance("XDH")
        ALGO_P256 -> KeyAgreement.getInstance("ECDH")
        else -> throw IllegalArgumentException("unknown key algorithm: $algorithm")
    }

    private fun sharedSecret(algorithm: String, privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = agreement(algorithm)
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        val raw = ka.generateSecret()
        // KDF: SHA-256 over the raw secret, domain-separated by algorithm.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("ilona-hk-wrap-v1".toByteArray(Charsets.UTF_8))
        digest.update(algorithm.toByteArray(Charsets.UTF_8))
        return digest.digest(raw)
    }

    /** Wrap [householdKey] so only the holder of [recipient]'s private key can open it. */
    fun wrapHouseholdKey(
        householdKey: ByteArray,
        recipient: DeviceKeyRecord,
        random: SecureRandom = SecureRandom(),
    ): String {
        val ephemeral = generateKeyPair(recipient.algorithm)
        val wrapKey = sharedSecret(
            recipient.algorithm,
            ephemeral.private,
            publicKey(recipient.algorithm, recipient.publicKeyB64),
        )
        val blob = WrapBlob(
            algorithm = recipient.algorithm,
            ephemeralPublicKeyB64 = Base64.getEncoder().encodeToString(ephemeral.public.encoded),
            wrappedKeyB64 = PayloadCipher.encrypt(wrapKey, householdKey, random),
        )
        return json.encodeToString(WrapBlob.serializer(), blob)
    }

    /** Open a wrap blob with this device's private key. Throws on tamper/wrong key. */
    fun unwrapHouseholdKey(wrapBlobJson: String, device: DeviceKeyRecord): ByteArray {
        val blob = json.decodeFromString(WrapBlob.serializer(), wrapBlobJson)
        require(blob.algorithm == device.algorithm) { "wrap algorithm does not match this device's key" }
        val wrapKey = sharedSecret(
            blob.algorithm,
            privateKey(device.algorithm, device.privateKeyB64),
            publicKey(blob.algorithm, blob.ephemeralPublicKeyB64),
        )
        return PayloadCipher.decrypt(wrapKey, blob.wrappedKeyB64)
    }

    /** Sanity helper for tests/diagnostics: an EC public key record check. */
    fun isPlausiblePublicKey(algorithm: String, b64: String): Boolean = try {
        val key = publicKey(algorithm, b64)
        algorithm != ALGO_P256 || key is ECPublicKey
    } catch (_: Exception) {
        false
    }
}
