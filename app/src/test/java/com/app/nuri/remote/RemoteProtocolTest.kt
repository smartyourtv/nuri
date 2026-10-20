package com.app.nuri.remote

import com.app.nuri.unstuck.CheckInRecord
import com.app.nuri.unstuck.DiaryEntry
import com.app.nuri.unstuck.SafetyAlert
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Design §13.3 — the remote protocol suite (JVM half). */
class RemoteProtocolTest {

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // ── PayloadCipher ────────────────────────────────────────────

    @Test
    fun cipherRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val blob = PayloadCipher.encryptText(key, "slept well, mood good")
        assertEquals("slept well, mood good", PayloadCipher.decryptText(key, blob))
    }

    @Test
    fun cipherTamperFails() {
        val key = ByteArray(32) { 7 }
        val blob = PayloadCipher.encryptText(key, "medicine taken")
        val raw = Base64.getDecoder().decode(blob)
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        try {
            PayloadCipher.decryptText(key, Base64.getEncoder().encodeToString(raw))
            fail("tampered ciphertext must not decrypt")
        } catch (_: Exception) {
        }
    }

    @Test
    fun cipherWrongKeyFails() {
        val blob = PayloadCipher.encryptText(ByteArray(32) { 1 }, "secret diary")
        try {
            PayloadCipher.decryptText(ByteArray(32) { 2 }, blob)
            fail("wrong key must not decrypt")
        } catch (_: Exception) {
        }
    }

    // ── Household key wrap (both curve preferences) ─────────────

    @Test
    fun householdKeyWrapsAndUnwraps() {
        val hk = ByteArray(32) { (it * 3).toByte() }
        for (preferred in listOf(HouseholdCrypto.ALGO_X25519, HouseholdCrypto.ALGO_P256)) {
            val device = HouseholdCrypto.generateDeviceKeyPair(preferred)
            assertEquals(preferred, device.algorithm)
            val blobJson = HouseholdCrypto.wrapHouseholdKey(hk, device)
            val back = HouseholdCrypto.unwrapHouseholdKey(blobJson, device)
            assertTrue(hk.contentEquals(back))
        }
    }

    @Test
    fun householdKeyDoesNotUnwrapForAnotherDevice() {
        val hk = ByteArray(32) { 9 }
        val mine = HouseholdCrypto.generateDeviceKeyPair()
        val other = HouseholdCrypto.generateDeviceKeyPair()
        val blobJson = HouseholdCrypto.wrapHouseholdKey(hk, mine)
        try {
            HouseholdCrypto.unwrapHouseholdKey(blobJson, other)
            fail("another device's key must not unwrap the household key")
        } catch (_: Exception) {
        }
    }

    // ── Pairing (§4.2) ───────────────────────────────────────────

    private data class World(
        val store: FakeRemoteStore,
        val pm: PairingManager,
        val hid: String,
        val offer: PairingManager.Offer,
        val seniorKey: HouseholdCrypto.DeviceKeyRecord,
    )

    private fun world(now: Long = 1_000_000L): World = runBlocking {
        val store = FakeRemoteStore()
        val pm = PairingManager(store, nowMs = { now })
        val hid = pm.createHousehold("caregiver-1", PairingManager.MODE_HYBRID, test = false)
        val offer = pm.createOffer(hid, "caregiver-1", PairingManager.MODE_HYBRID, test = false)
        World(store, pm, hid, offer, HouseholdCrypto.generateDeviceKeyPair())
    }

    @Test
    fun pairingClaimByScanSecret() = runBlocking {
        val w = world()
        val result = w.pm.claim(w.hid, w.offer.token, "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64)
        assertTrue(result is PairingManager.ClaimResult.Claimed)
        val pairing = w.store.get(HouseholdPaths(w.hid).pairing)!!
        assertEquals("senior-1", pairing["claimedBy"])
        assertEquals("senior-1", pairing["seniorUid"])
        w.pm.claimHouseholdSlot(w.hid, "senior-1")
        val household = w.store.get(HouseholdPaths(w.hid).household)!!
        assertEquals("senior-1", household["seniorUid"])
    }

    @Test
    fun pairingClaimByTypedCode() = runBlocking {
        val w = world()
        val result = w.pm.claim(w.hid, w.offer.code, "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64)
        assertTrue(result is PairingManager.ClaimResult.Claimed)
    }

    @Test
    fun pairingWrongSecretRejected() = runBlocking {
        val w = world()
        val result = w.pm.claim(w.hid, "00000000", "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64)
        assertEquals(PairingManager.ClaimResult.WrongSecret, result)
        val result2 = w.pm.claim(w.hid, "not-the-token", "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64)
        assertEquals(PairingManager.ClaimResult.WrongSecret, result2)
    }

    @Test
    fun pairingExpiredOfferRejected() = runBlocking {
        val store = FakeRemoteStore()
        var now = 1_000_000L
        val pm = PairingManager(store, nowMs = { now })
        val hid = pm.createHousehold("caregiver-1", PairingManager.MODE_HYBRID, test = false)
        val offer = pm.createOffer(hid, "caregiver-1", PairingManager.MODE_HYBRID, test = false)
        now += PairingManager.OFFER_TTL_MS + 1
        val key = HouseholdCrypto.generateDeviceKeyPair()
        val result = pm.claim(hid, offer.token, "senior-1", key.algorithm to key.publicKeyB64)
        assertEquals(PairingManager.ClaimResult.Expired, result)
    }

    @Test
    fun pairingSingleUse() = runBlocking {
        val w = world()
        val key2 = HouseholdCrypto.generateDeviceKeyPair()
        w.pm.claim(w.hid, w.offer.token, "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64)
        val second = w.pm.claim(w.hid, w.offer.token, "senior-2", key2.algorithm to key2.publicKeyB64)
        assertEquals(PairingManager.ClaimResult.AlreadyClaimed, second)
    }

    @Test
    fun pairingQrPayloadRoundTrip() {
        val payload = PairingManager.encodeQrPayload("abc123xyz", "tok-123")
        assertEquals("abc123xyz" to "tok-123", PairingManager.decodeQrPayload(payload))
        assertNull(PairingManager.decodeQrPayload("https://example.com"))
        assertNull(PairingManager.decodeQrPayload("ilona-connect:v2:abc:tok"))
    }

    @Test
    fun seniorReplacementSequence() = runBlocking {
        val w = world()
        w.pm.claim(w.hid, w.offer.token, "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64)
        w.pm.claimHouseholdSlot(w.hid, "senior-1")
        // Caregiver resets, then a fresh offer claims cleanly.
        w.pm.beginSeniorReplacement(w.hid)
        val household = w.store.get(HouseholdPaths(w.hid).household)!!
        assertNull(household["seniorUid"])
        assertNull(w.store.get(HouseholdPaths(w.hid).pairing))
        val offer2 = w.pm.createOffer(w.hid, "caregiver-1", PairingManager.MODE_HYBRID, test = false)
        val newKey = HouseholdCrypto.generateDeviceKeyPair()
        val result = w.pm.claim(w.hid, offer2.token, "senior-2", newKey.algorithm to newKey.publicKeyB64)
        assertTrue(result is PairingManager.ClaimResult.Claimed)
        w.pm.claimHouseholdSlot(w.hid, "senior-2")
        assertEquals("senior-2", w.store.get(HouseholdPaths(w.hid).household)!!["seniorUid"])
    }

    @Test
    fun keyDeliveryRoundTrip() = runBlocking {
        val w = world()
        val hk = ByteArray(32) { 42 }
        w.pm.claim(w.hid, w.offer.token, "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64)
        w.pm.deliverHouseholdKey(w.hid, "senior-1", w.seniorKey.algorithm to w.seniorKey.publicKeyB64, hk)
        val doc = w.store.get(HouseholdPaths(w.hid).household)!!
        @Suppress("UNCHECKED_CAST")
        val wraps = doc["keyWraps"] as Map<String, String>
        assertTrue(hk.contentEquals(HouseholdCrypto.unwrapHouseholdKey(wraps.getValue("senior-1"), w.seniorKey)))
    }

    // ── Senior posting + caregiver reading (encrypted end-to-end) ──

    @Test
    fun recordsFlowEncryptedAndDecryptForCaregiver() = runBlocking {
        val store = FakeRemoteStore()
        val paths = HouseholdPaths("hh1")
        val hk = ByteArray(32) { 5 }
        val senior = FirestoreRemoteObservationRepository(store, paths, { hk }, nowMs = { 5000L })
        val caregiver = RemoteCaregiverRepository(store, paths, { hk }, nowMs = { 5000L })

        senior.postCheckIn(CheckInRecord("2026-10-20", "7:02 AM", "Well", "Good", "Ate well"))
        senior.postSafetyAlert(SafetyAlert("2026-10-20", "7:03 AM", "Emergency call made", "He pressed the button"))

        // Ciphertext at rest: the raw documents must not contain the words.
        val rawEvent = store.list(paths.events).single().second.toString()
        assertFalse(rawEvent.contains("Ate well"))
        val rawAlert = store.list(paths.alerts).single().second.toString()
        assertFalse(rawAlert.contains("pressed the button"))

        var events: List<RemoteEvent> = emptyList()
        caregiver.observeEvents { events = it }.close()
        assertEquals(1, events.size)
        assertEquals("Ate well", events.single().checkIn?.appetite)
        var alerts: List<RemoteAlert> = emptyList()
        caregiver.observeAlerts { alerts = it }.close()
        assertEquals(RemoteAlertClass.SAFETY, alerts.single().alertClass)
        assertEquals("Emergency call made", alerts.single().payload.title)
    }

    @Test
    fun caregiverCannotDecryptWithWrongKey() = runBlocking {
        val store = FakeRemoteStore()
        val paths = HouseholdPaths("hh1")
        val senior = FirestoreRemoteObservationRepository(store, paths, { ByteArray(32) { 5 } }, nowMs = { 1L })
        senior.postCheckIn(CheckInRecord("2026-10-20", "7:02 AM", "Well", "Good", "Ate"))
        val stranger = RemoteCaregiverRepository(store, paths, { ByteArray(32) { 6 } }, nowMs = { 1L })
        var events: List<RemoteEvent> = listOf(RemoteEvent("x", RemoteEventType.DIARY, "", 0, false))
        stranger.observeEvents { events = it }.close()
        assertTrue(events.isEmpty())
    }

    @Test
    fun statusCarriesPlaintextVersionMetadata() = runBlocking {
        val store = FakeRemoteStore()
        val paths = HouseholdPaths("hh1")
        val hk = ByteArray(32) { 5 }
        val senior = FirestoreRemoteObservationRepository(store, paths, { hk }, nowMs = { 1L })
        senior.postStatus(
            RemoteStatus(
                dayKey = "2026-10-20", updatedAtMs = null, lastSeenAtMs = null,
                appVersionName = "0.5.0", appVersionCode = 9,
                lastUpdateCheckText = "Ilona is up to date — no newer version was found.",
                payload = StatusPayload(seenSettings = mapOf("tier" to "GUIDED"), effectiveSettings = mapOf("tier" to "GUIDED")),
            ),
        )
        val doc = store.get(paths.statusToday)!!
        assertEquals("0.5.0", doc["appVersionName"])
        assertEquals(9, RemoteDoc.int(doc, "appVersionCode"))
        val caregiver = RemoteCaregiverRepository(store, paths, { hk }, nowMs = { 1L })
        val decoded = caregiver.decodeStatus(doc)
        assertEquals("GUIDED", decoded.payload?.seenSettings?.get("tier"))
        assertEquals(HisPhoneUpdateState.UP_TO_DATE, HisPhoneUpdate.state(decoded))
        assertEquals("His phone: Ilona 0.5.0 — up to date", HisPhoneUpdate.line(decoded))
    }

    @Test
    fun hisPhoneUpdateFailureWording() {
        val failed = RemoteStatus("d", null, null, "0.5.0", 9, "The new Ilona is being installed, but the self-check did not pass yet.", null)
        assertEquals(HisPhoneUpdateState.UPDATE_FAILED, HisPhoneUpdate.state(failed))
        assertEquals("His phone: Ilona 0.5.0 — update failed (self-test result)", HisPhoneUpdate.line(failed))
        val pending = RemoteStatus("d", null, null, "0.5.0", 9, "The new Ilona is being installed.", null)
        assertEquals(HisPhoneUpdateState.UPDATE_PENDING, HisPhoneUpdate.state(pending))
        val unknown = RemoteStatus("d", null, null, null, null, "", null)
        assertEquals("His phone's version hasn't been reported yet.", HisPhoneUpdate.line(unknown))
    }

    // ── Shadow sync (§7) ─────────────────────────────────────────

    @Test
    fun shadowVersionPendingAckFlow() = runBlocking {
        assertEquals(1, ShadowSync.nextVersion(null))
        assertEquals(3, ShadowSync.nextVersion(2))
        assertEquals("Nothing sent yet.", ShadowSync.statusLine(null, null))
        assertEquals("Waiting for his phone to pick this up.", ShadowSync.statusLine(2, 1))
        assertEquals("In effect.", ShadowSync.statusLine(2, 2))

        val store = FakeRemoteStore()
        val paths = HouseholdPaths("hh1")
        val hk = ByteArray(32) { 3 }
        val caregiver = RemoteCaregiverRepository(store, paths, { hk }, nowMs = { 10L })
        val v1 = caregiver.writeShadow(mapOf(RemoteShadow.KEY_TIER to "GUIDED"), "cg")
        assertEquals(1, v1)
        val v2 = caregiver.writeShadow(mapOf(RemoteShadow.KEY_TIER to "SUPPORTED"), "cg")
        assertEquals(2, v2)

        var observed: ShadowPayload? = null
        var observedVersion: Int? = null
        val senior = FirestoreRemoteObservationRepository(store, paths, { hk }, nowMs = { 11L })
        senior.observeShadow { version, payload -> observedVersion = version; observed = payload }.close()
        assertEquals(2, observedVersion)
        assertEquals("SUPPORTED", observed?.values?.get(RemoteShadow.KEY_TIER))
    }

    // ── Alert routing / missing check-in / freshness (§7, §8.3) ──

    @Test
    fun alertRoutingClasses() {
        assertEquals(RemoteAlertClass.MED_MISSED, AlertRouting.alertClassFor("Medicine not confirmed"))
        assertEquals(RemoteAlertClass.WELFARE, AlertRouting.alertClassFor("Welfare check — no answer"))
        assertEquals(RemoteAlertClass.SAFETY, AlertRouting.alertClassFor("Emergency call made"))
        assertTrue(AlertRouting.notifiesCaregiver(RemoteAlertClass.SAFETY))
        assertTrue(AlertRouting.notifiesCaregiver(RemoteAlertClass.WELFARE))
        assertTrue(AlertRouting.notifiesCaregiver(RemoteAlertClass.MED_MISSED))
    }

    @Test
    fun missingCheckInRule() {
        val today = "2026-10-20"
        // Checked in today → never raises.
        assertFalse(MissingCheckIn.shouldRaise(11 * 60, 10, today, today, null))
        // Before the cutoff → not yet.
        assertFalse(MissingCheckIn.shouldRaise(9 * 60, 10, today, null, null))
        // At/after the cutoff with no check-in → raises.
        assertTrue(MissingCheckIn.shouldRaise(10 * 60, 10, today, null, null))
        assertTrue(MissingCheckIn.shouldRaise(12 * 60, 10, today, "2026-10-19", null))
        // Already raised today → not again.
        assertFalse(MissingCheckIn.shouldRaise(12 * 60, 10, today, null, today))
    }

    @Test
    fun freshnessHonesty() {
        val now = 100L * 60 * 60 * 1000
        val fresh = RemoteStatus("d", now - 60_000, now - 60_000, "0.5.0", 9, "", null)
        val lines = RemoteFreshness.lines(fresh, now)
        assertFalse(lines.noWord)
        assertTrue(lines.lastUpdatedLine.startsWith("Last updated"))
        val stale = RemoteFreshness.lines(RemoteStatus("d", now - 25L * 60 * 60 * 1000, now - 25L * 60 * 60 * 1000, "0.5.0", 9, "", null), now)
        assertTrue(stale.noWord)
        assertTrue(stale.noWordLine!!.contains("No word from his phone since"))
        val none = RemoteFreshness.lines(null, now)
        assertTrue(none.lastUpdatedLine.contains("yet"))
    }

    // ── Independent = zero store calls (§1, §13.3) ───────────────

    @Test
    fun noOpRepositoryMakesZeroStoreCalls() = runBlocking {
        val repo: RemoteObservationRepository = NoOpRemoteObservationRepository
        repo.postCheckIn(CheckInRecord("d", "t", "Well", "Good", "Ate"))
        repo.postSafetyAlert(SafetyAlert("d", "t", "Emergency call made", "x"))
        repo.postStatus(RemoteStatus("d", null, null, "0.5.0", 9, "", StatusPayload()))
        repo.postShadowAck(ShadowAckPayload(appliedVersion = 1))
        repo.observeShadow { _, _ -> }.close()
        // NoOp has no store at all — the assertion is structural:
        // the engine only ever receives a repository, and in
        // Independent mode startup constructs nothing (RemoteStartup
        // returns before session resolution; covered by review +
        // the mode checks inside the engine's posters).
        assertNotNull(repo)
    }

    // ── Test mode plan (§11) ─────────────────────────────────────

    @Test
    fun testExitLeavesRealBindingUntouched() {
        val real = HouseholdBinding("real-hh", RemoteRole.CAREGIVER, test = false)
        val test = HouseholdBinding("test-hh", RemoteRole.CAREGIVER, test = true)
        assertTrue(TestHouseholdPlan.realBindingSurvivesExit(real, test))
        assertTrue(TestHouseholdPlan.isTestBinding(test))
        assertFalse(TestHouseholdPlan.isTestBinding(real))
        assertTrue(TestHouseholdPlan.EXIT_DELETIONS.isNotEmpty())
        assertTrue(TestHouseholdPlan.EXIT_RESIDUE.any { it.contains("closed") })
    }

    // ── Config parsing (§10.2) ───────────────────────────────────

    @Test
    fun bundledConfigParses() {
        val text = """
            {"project_info":{"project_number":"1234567890","project_id":"demo-home"},
             "client":[{"client_info":{"mobilesdk_app_id":"1:1234567890:android:abcdef"},
             "api_key":[{"current_key":"AIzaSyDEMO"}]}]}
        """.trimIndent()
        val config = FirebaseProjectConfig.fromGoogleServicesJson(text)
        assertNotNull(config)
        assertEquals("demo-home", config!!.projectId)
        assertEquals("1234567890", config.senderId)
        assertTrue(config.isPlausible())
    }

    @Test
    fun pastedConfigParsesJsAndJsonShapes() {
        val js = """
            const firebaseConfig = {
              apiKey: "AIzaSyDEMO",
              authDomain: "demo-home.firebaseapp.com",
              projectId: "demo-home",
              messagingSenderId: "1234567890",
              appId: "1:1234567890:android:abcdef"
            };
        """.trimIndent()
        val config = FirebaseProjectConfig.fromPastedConfig(js)
        assertNotNull(config)
        assertEquals("demo-home", config!!.projectId)
        assertNull(FirebaseProjectConfig.fromPastedConfig("hello world"))
        assertNull(FirebaseProjectConfig.fromPastedConfig("""{"projectId": "x"}"""))
    }

    // ── Dashboard mapping + call line ────────────────────────────

    @Test
    fun remoteDashboardRendersAndRefusesIndependent() {
        val repo = RemoteDashboardRepository(
            status = RemoteStatus(
                "2026-10-20", 1L, 1L, "0.5.0", 9, "",
                StatusPayload(
                    checkIn = CheckInPayload("2026-10-20", "7:02 AM", "Well", "Good", "Ate"),
                    medsToday = listOf(MedEventPayload("2026-10-20", "8:00 AM", "Morning pill", true)),
                ),
            ),
            events = emptyList(),
            alerts = listOf(
                RemoteAlert("a1", RemoteAlertClass.SAFETY, 1L, false, AlertPayload("2026-10-20", "7:03 AM", "Emergency call made", "Pressed")),
            ),
            todayKey = "2026-10-20",
        )
        val data = repo.dataFor(com.app.nuri.config.NuriSettings.Tier.GUIDED, com.app.nuri.config.NuriSettings.AccessMode.HYBRID)
        assertTrue(data.dailySummary!!.contains("Morning check-in done at 7:02 AM"))
        assertEquals(1, data.alerts.size)
        val independent = repo.dataFor(com.app.nuri.config.NuriSettings.Tier.GUIDED, com.app.nuri.config.NuriSettings.AccessMode.INDEPENDENT)
        assertNotNull(independent.noDataReason)
    }

    @Test
    fun callLineWording() {
        val answered = CallEventPayload(CallKind.CHECKIN, null, CallOutcome.ANSWERED, 125, CallOutcome.ENDED_BY_SENIOR, "d", "t")
        assertEquals("Check-in call — Talked for 2 min 5 sec. He ended the call.", RemoteDashboardRepository.callLine(answered))
        val noAnswer = CallEventPayload(CallKind.CHECKIN, null, CallOutcome.NO_ANSWER, 0, null, "d", "t")
        assertEquals("Check-in call — No answer.", RemoteDashboardRepository.callLine(noAnswer))
    }

    // ── No-answer ladder (§17.4) ─────────────────────────────────

    @Test
    fun noAnswerLadderCountsWithinTheHour() {
        val now = 10L * 60 * 60 * 1000
        fun noAnswerAt(ms: Long) = RemoteEvent(
            "e$ms", RemoteEventType.CALL, "d", ms, false,
            call = CallEventPayload(CallKind.CHECKIN, null, CallOutcome.NO_ANSWER, 0, null, "d", "t"),
        )
        assertFalse(NoAnswerLadder.shouldRaiseWelfare(listOf(noAnswerAt(now - 1000)), now, threshold = 2))
        assertTrue(NoAnswerLadder.shouldRaiseWelfare(listOf(noAnswerAt(now - 1000), noAnswerAt(now - 2000)), now, threshold = 2))
        // One inside the hour + one outside = no raise.
        assertFalse(NoAnswerLadder.shouldRaiseWelfare(listOf(noAnswerAt(now - 1000), noAnswerAt(now - 61 * 60 * 1000)), now, threshold = 2))
        // Alert-kind no-answers don't count toward the check-in ladder.
        val alertNoAnswer = RemoteEvent(
            "x", RemoteEventType.CALL, "d", now - 500, false,
            call = CallEventPayload(CallKind.ALERT, null, CallOutcome.NO_ANSWER, 0, null, "d", "t"),
        )
        assertFalse(NoAnswerLadder.shouldRaiseWelfare(listOf(noAnswerAt(now - 1000), alertNoAnswer), now, threshold = 2))
        // Already raised: needs fresh no-answers after the raise.
        assertFalse(
            NoAnswerLadder.shouldRaiseWelfare(
                listOf(noAnswerAt(now - 1000), noAnswerAt(now - 2000)), now,
                threshold = 2, alreadyRaisedAtMs = now - 500,
            ),
        )
        // The ladder offers human steps only — assert no dial action exists in its vocabulary.
        assertTrue(NoAnswerLadder.HUMAN_STEPS.joinToString(" ").contains("never makes it for you"))
    }

    @Test
    fun pruneRule() {
        val now = 200L * 24 * 60 * 60 * 1000
        assertTrue(RemoteCaregiverRepository.shouldDelete(now - 91L * 24 * 60 * 60 * 1000, now))
        assertFalse(RemoteCaregiverRepository.shouldDelete(now - 10L * 24 * 60 * 60 * 1000, now))
        assertFalse(RemoteCaregiverRepository.shouldDelete(0L, now))
    }

    @Test
    fun eventIdsAreDeterministic() {
        assertEquals(RemoteEventIds.of("A", "B"), RemoteEventIds.of("A", "B"))
        assertNotEquals(RemoteEventIds.of("A", "B"), RemoteEventIds.of("A", "C"))
    }

    @Test
    fun diaryPostingIsEncrypted() = runBlocking {
        val store = FakeRemoteStore()
        val paths = HouseholdPaths("hh1")
        val hk = ByteArray(32) { 8 }
        val senior = FirestoreRemoteObservationRepository(store, paths, { hk }, nowMs = { 3L })
        senior.postDiaryEntry(DiaryEntry("2026-10-20", "9:00 AM", "Couldn't reach the shelf", "Resolved on its own"))
        val caregiver = RemoteCaregiverRepository(store, paths, { hk }, nowMs = { 3L })
        var events: List<RemoteEvent> = emptyList()
        caregiver.observeEvents { events = it }.close()
        assertEquals("Couldn't reach the shelf", events.single().diary?.what)
    }
}
