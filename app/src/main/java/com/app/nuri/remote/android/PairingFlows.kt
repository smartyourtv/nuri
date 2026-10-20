package com.app.nuri.remote.android

import android.content.Context
import com.app.nuri.remote.FirebaseProjectConfig
import com.app.nuri.remote.HouseholdCrypto
import com.app.nuri.remote.HouseholdPaths
import com.app.nuri.remote.PairingManager
import com.app.nuri.remote.RemoteDoc
import com.app.nuri.remote.TestHouseholdPlan
import java.security.SecureRandom
import java.util.Base64

/**
 * The Android execution of the connecting flows (design §4) over
 * real Firebase identities. All protocol decisions live in the
 * pure layer ([PairingManager], [HouseholdCrypto]); this object
 * only wires sessions, key custody, and persistence. Screens drive
 * these functions — tap counts are counted in the UI layer and in
 * the status record (Amendment 1), not here.
 */
object PairingFlows {

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** A caregiver session before any household exists (hid placeholder — paths are per-call). */
    suspend fun caregiverSession(
        context: Context,
        config: FirebaseProjectConfig,
        test: Boolean,
    ): RemoteSession? {
        val appName = if (test) TestHouseholdPlan.TEST_APP_NAME else null
        // Household id is created by the flow; the keystore tag is
        // per-household and opened after creation — a placeholder
        // tag session is only used for store access here.
        return FirebaseInit.session(context, config, appName, householdId = "pending", role = "CAREGIVER", test = test)
    }

    /**
     * Caregiver step 1: create the household + the household key on
     * this phone. Returns the household id. The key lives in this
     * binding's key store from this moment (§5.2 step 1).
     */
    suspend fun caregiverCreateHousehold(
        context: Context,
        config: FirebaseProjectConfig,
        mode: String,
        test: Boolean,
    ): Pair<String, RemoteSession>? {
        val appName = if (test) TestHouseholdPlan.TEST_APP_NAME else null
        val probe = FirebaseInit.session(context, config, appName, "pending", "CAREGIVER", test) ?: return null
        val pm = PairingManager(probe.store)
        val hid = pm.createHousehold(probe.uid, mode, test)
        val session = FirebaseInit.session(context, config, appName, hid, "CAREGIVER", test) ?: return null
        if (session.keyStore.householdKey() == null) {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            session.keyStore.saveHouseholdKey(key)
        }
        return hid to session
    }

    /** Caregiver step 2: a fresh offer for the dashboard's connect screen. */
    suspend fun caregiverCreateOffer(
        session: RemoteSession,
        mode: String,
        test: Boolean,
        carryOverKey: Pair<String, String>? = null,
    ): PairingManager.Offer =
        PairingManager(session.store).createOffer(session.paths.householdId, session.uid, mode, test, carryOverKey)

    /**
     * Senior claim: present the scanned secret or the typed code.
     * On success the binding is live (slot taken, this phone's
     * device key registered); the household key arrives when the
     * caregiver's phone delivers its wrap (observed by the sync
     * engine). Returns the claim result for the screen to narrate.
     */
    suspend fun seniorClaim(
        context: Context,
        config: FirebaseProjectConfig,
        householdId: String,
        secret: String,
        test: Boolean,
    ): Pair<PairingManager.ClaimResult, RemoteSession?> {
        // The senior role always uses the default app instance —
        // in test mode the default instance plays the senior (§11.1).
        val session = FirebaseInit.session(context, config, null, householdId, "SENIOR", test)
            ?: return PairingManager.ClaimResult.NoOffer to null
        val deviceKey = session.keyStore.deviceKey()
        val pm = PairingManager(session.store)
        val result = pm.claim(
            householdId, secret, session.uid,
            deviceKey.algorithm to deviceKey.publicKeyB64,
        )
        if (result is PairingManager.ClaimResult.Claimed) {
            try {
                pm.claimHouseholdSlot(householdId, session.uid)
            } catch (e: Exception) {
                // Slot already held (rules deny the blind update):
                // the claim does not complete.
                return PairingManager.ClaimResult.NoOffer to session
            }
            // Caregiver-replacement carry-over (§4.3): the offer
            // carried the NEW caregiver's public key — wrap the HK
            // we already hold forward to them and record the wrap
            // where they can read it (the household document).
            result.caregiverPublicKey?.let { (algo, pub) ->
                session.keyStore.householdKey()?.let { hk ->
                    val recipient = HouseholdCrypto.DeviceKeyRecord(algo, pub, "")
                    val blobJson = HouseholdCrypto.wrapHouseholdKey(hk, recipient)
                    session.store.update(
                        session.paths.household,
                        mapOf("keyWraps" to mapOf("carryover" to blobJson)),
                    )
                }
            }
        }
        return result to session
    }

    /**
     * Caregiver key delivery: once the offer shows claimed, wrap
     * this phone's household key to the senior phone's public key
     * and record it (§5.2 steps 3–4). Idempotent.
     */
    suspend fun caregiverDeliverKey(session: RemoteSession) {
        val pairing = session.store.get(session.paths.pairing) ?: return
        val seniorUid = RemoteDoc.str(pairing, "claimedBy") ?: return
        val algo = RemoteDoc.str(pairing, "seniorKeyAlgo") ?: return
        val pub = RemoteDoc.str(pairing, "seniorPublicKey") ?: return
        val hk = session.keyStore.householdKey() ?: return
        PairingManager(session.store).deliverHouseholdKey(session.paths.householdId, seniorUid, algo to pub, hk)
    }

    /**
     * Senior key receipt (§5.2 step 5): read this phone's wrap off
     * the household document and unwrap it with the device key.
     * Returns true when the household key is now held.
     */
    suspend fun seniorTryReceiveKey(session: RemoteSession): Boolean {
        if (session.keyStore.householdKey() != null) return true
        val doc = session.store.get(session.paths.household) ?: return false
        @Suppress("UNCHECKED_CAST")
        val wraps = doc["keyWraps"] as? Map<String, Any?> ?: return false
        val blobJson = wraps[session.uid] as? String ?: return false
        val deviceKey = session.keyStore.deviceKey()
        val hk = try {
            HouseholdCrypto.unwrapHouseholdKey(blobJson, deviceKey)
        } catch (_: Exception) {
            return false
        }
        session.keyStore.saveHouseholdKey(hk)
        return true
    }
}
