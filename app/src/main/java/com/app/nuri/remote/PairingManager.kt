package com.app.nuri.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The connecting flow (design §4.2/§4.3): one side creates a
 * household and a short-lived offer; the other side claims the
 * senior slot by presenting the offer's secret once. The secret is
 * a 128-bit random value (shown as a scannable code) with an
 * 8-digit fallback code; only SHA-256 hashes of either are stored.
 * Offers expire in 10 minutes and are single-use; the Firestore
 * rules enforce role/expiry/single-use at the database, and the
 * claim transaction re-verifies the presented secret here.
 *
 * User-facing wording NEVER uses the words in this file's comments
 * (Amendment 1: no "household", "token", "pairing", "UID" on any
 * surface) — screens say "watching", "connect", and "code".
 */
class PairingManager(
    private val store: RemoteStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val OFFER_TTL_MS: Long = 10 * 60 * 1000L
        const val MODE_HYBRID = "HYBRID"
        const val MODE_MANAGED = "MANAGED"

        fun hashToken(token: String): String = sha256Hex(token)
        fun hashCode(householdId: String, code: String): String = sha256Hex("$householdId:$code")

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** The screen code as displayed: grouped for reading aloud/typing. */
        fun displayId(householdId: String): String = householdId.chunked(3).joinToString(" ")

        /** Normalize a typed screen code back to the id. */
        fun normalizeId(typed: String): String = typed.lowercase().replace(" ", "").replace("-", "")

        /** The scannable content: everything the claiming phone needs. */
        fun encodeQrPayload(householdId: String, token: String): String =
            "ilona-connect:v1:$householdId:$token"

        fun decodeQrPayload(text: String): Pair<String, String>? {
            val parts = text.trim().split(":")
            if (parts.size != 4 || parts[0] != "ilona-connect" || parts[1] != "v1") return null
            if (parts[2].isBlank() || parts[3].isBlank()) return null
            return parts[2] to parts[3]
        }
    }

    data class Offer(
        val householdId: String,
        /** Full secret — shown on the creating phone only, never stored. */
        val token: String,
        /** 8-digit fallback code — the only typing that exists (Amendment 1). */
        val code: String,
        val expiresAtMs: Long,
    ) {
        val qrPayload: String get() = encodeQrPayload(householdId, token)
    }

    /**
     * Household ids are routing identifiers, not secrets (the
     * offer's hashes are the secrets, and the rules gate everything
     * by membership) — so they use a short, typeable alphabet with
     * no lookalike characters. The scan path never needs typing;
     * the typed fallback (Amendment 1) enters this code alongside
     * the 8 digits, which is why it must be human-scale:
     * 9 characters ≈ 45 bits, shown grouped 3-3-3.
     */
    private fun newId(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val sb = StringBuilder(9)
        repeat(9) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }



    private fun newToken(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) } // 128-bit
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun newCode(): String = "%08d".format(random.nextInt(100_000_000))

    /**
     * Create a household for [caregiverUid] and return its id.
     * [test] marks a test household (design §11) — visible in the
     * document so the real and test households can never be confused.
     */
    suspend fun createHousehold(caregiverUid: String, mode: String, test: Boolean): String {
        val hid = newId()
        store.set(
            HouseholdPaths(hid).household,
            mapOf(
                "seniorUid" to null,
                "caregiverUids" to listOf(caregiverUid),
                "mode" to mode,
                "createdAt" to nowMs(),
                "schemaVersion" to 1,
                "test" to test,
            ),
        )
        return hid
    }

    /**
     * Create (or replace) the current offer on [householdId]. The
     * offer document also carries the household's mode + test flag
     * so the claiming phone adopts the right local settings, and —
     * on the caregiver-replacement path only (§4.3 carry-over) —
     * the NEW caregiver's public key, so the senior phone can wrap
     * the household key forward. [expiresAt] is stored as a real
     * Firestore Timestamp: the published rules compare it to
     * request.time.
     */
    suspend fun createOffer(
        householdId: String,
        caregiverUid: String,
        mode: String,
        test: Boolean,
        caregiverPublicKey: Pair<String, String>? = null, // (algorithm, b64)
    ): Offer {
        val token = newToken()
        val code = newCode()
        val expires = nowMs() + OFFER_TTL_MS
        store.set(
            HouseholdPaths(householdId).pairing,
            mapOf(
                "tokenHash" to hashToken(token),
                "codeHash" to hashCode(householdId, code),
                "caregiverUid" to caregiverUid,
                "expiresAt" to RemoteTimestamp(expires),
                "claimedBy" to null,
                "mode" to mode,
                "test" to test,
                "caregiverKeyAlgo" to caregiverPublicKey?.first,
                "caregiverPublicKey" to caregiverPublicKey?.second,
            ),
        )
        return Offer(householdId, token, code, expires)
    }

    sealed interface ClaimResult {
        /**
         * Claimed. [caregiverPublicKey] = (algorithm, b64) when the
         * offer carried one (caregiver-replacement carry-over);
         * [mode]/[test] are the household's, for local settings.
         */
        data class Claimed(
            val caregiverPublicKey: Pair<String, String>?,
            val mode: String,
            val test: Boolean,
        ) : ClaimResult
        data object NoOffer : ClaimResult
        data object Expired : ClaimResult
        data object AlreadyClaimed : ClaimResult
        data object WrongSecret : ClaimResult
    }

    /**
     * Claim the offer of [householdId] for [seniorUid], presenting
     * either the full secret (scanned) or the 8-digit code
     * (typed). The transaction touches the OFFER document only:
     * under the published rules a not-yet-member cannot READ the
     * household document, and the offer document is the single-use
     * lock (its update rule closes the moment claimedBy is set).
     * The household slot itself is taken in a second step,
     * [claimHouseholdSlot] — a blind partial update the rules
     * explicitly allow (claim shape: empty slot, caller becomes
     * the senior, caregiver list unchanged). [seniorPublicKey] =
     * (algorithm, b64) of this phone's device key, recorded on the
     * offer for the caregiver to wrap the household key to.
     */
    suspend fun claim(
        householdId: String,
        presentedSecret: String,
        seniorUid: String,
        seniorPublicKey: Pair<String, String>,
    ): ClaimResult {
        val paths = HouseholdPaths(householdId)
        val presented = presentedSecret.trim()
        return store.transaction { tx ->
            val offer = tx.get(paths.pairing) ?: return@transaction ClaimResult.NoOffer
            if (RemoteDoc.str(offer, "claimedBy") != null) return@transaction ClaimResult.AlreadyClaimed
            val expires = RemoteDoc.timestampMs(offer, "expiresAt") ?: 0L
            if (expires <= nowMs()) return@transaction ClaimResult.Expired
            val tokenOk = hashToken(presented) == RemoteDoc.str(offer, "tokenHash")
            val codeOk = presented.length == 8 && presented.all { it.isDigit() } &&
                hashCode(householdId, presented) == RemoteDoc.str(offer, "codeHash")
            if (!tokenOk && !codeOk) return@transaction ClaimResult.WrongSecret
            tx.update(
                paths.pairing,
                mapOf(
                    "claimedBy" to seniorUid,
                    "seniorUid" to seniorUid,
                    "seniorKeyAlgo" to seniorPublicKey.first,
                    "seniorPublicKey" to seniorPublicKey.second,
                    "claimedAt" to nowMs(),
                ),
            )
            val cgAlgo = RemoteDoc.str(offer, "caregiverKeyAlgo")
            val cgPub = RemoteDoc.str(offer, "caregiverPublicKey")
            ClaimResult.Claimed(
                caregiverPublicKey = if (cgAlgo != null && cgPub != null) cgAlgo to cgPub else null,
                mode = RemoteDoc.str(offer, "mode") ?: MODE_HYBRID,
                test = RemoteDoc.bool(offer, "test") ?: false,
            )
        }
    }

    /**
     * Step 2 of the claim: take the household's senior slot with a
     * blind partial update (the rules' claim shape). Throws when
     * the slot is occupied — the caller surfaces "this phone is
     * already connected" honestly rather than retrying silently.
     */
    suspend fun claimHouseholdSlot(householdId: String, seniorUid: String) {
        store.update(
            HouseholdPaths(householdId).household,
            mapOf("seniorUid" to seniorUid),
        )
    }

    /**
     * The caregiver's half of key delivery (§5.2 step 3–4): wrap
     * [householdKey] to the senior phone's public key from the
     * claimed offer and record the wrap on the household document,
     * where the senior phone (now a member, so able to read it)
     * picks it up.
     */
    suspend fun deliverHouseholdKey(
        householdId: String,
        seniorUid: String,
        seniorPublicKey: Pair<String, String>,
        householdKey: ByteArray,
    ) {
        val recipient = HouseholdCrypto.DeviceKeyRecord(
            algorithm = seniorPublicKey.first,
            publicKeyB64 = seniorPublicKey.second,
            privateKeyB64 = "",
        )
        val blobJson = HouseholdCrypto.wrapHouseholdKey(householdKey, recipient)
        store.update(
            HouseholdPaths(householdId).household,
            mapOf("keyWraps" to mapOf(seniorUid to blobJson)),
        )
    }

    /**
     * Replacement of the senior phone (§4.3): the caregiver clears
     * the senior slot (the published rules allow a claim only into
     * an empty slot; a caregiver update is unrestricted), after
     * which a fresh offer + claim proceeds exactly like first-time
     * connecting. The old phone stops matching the household the
     * moment the new claim lands.
     */
    suspend fun beginSeniorReplacement(householdId: String) {
        val paths = HouseholdPaths(householdId)
        store.update(
            paths.household,
            mapOf("seniorUid" to null, "keyWraps" to null),
        )
        store.delete(paths.pairing)
    }

    /** Caregiver marks a (test) household closed at exit (design §11.3). */
    suspend fun markClosed(householdId: String) {
        store.update(HouseholdPaths(householdId).household, mapOf("closed" to true, "closedAt" to nowMs()))
    }
}

/**
 * Shadow version/pending/ack logic (design §7), pure and tested:
 * versions are monotonic; a caregiver write is PENDING until the
 * senior phone's ack names the same version APPLIED.
 */
object ShadowSync {
    const val STATE_PENDING = "PENDING"
    const val STATE_APPLIED = "APPLIED"

    fun nextVersion(currentVersion: Int?): Int = (currentVersion ?: 0) + 1

    /** Caregiver-side status for [shadowVersion] given the latest ack. */
    fun statusLine(shadowVersion: Int?, ackedVersion: Int?): String = when {
        shadowVersion == null -> "Nothing sent yet."
        ackedVersion != null && ackedVersion >= shadowVersion -> "In effect."
        else -> "Waiting for his phone to pick this up."
    }

    fun isApplied(shadowVersion: Int?, ackedVersion: Int?): Boolean =
        shadowVersion != null && ackedVersion != null && ackedVersion >= shadowVersion
}
