package com.app.nuri.remote

import com.app.nuri.unstuck.CheckInRecord
import com.app.nuri.unstuck.DiaryEntry
import com.app.nuri.unstuck.MedConfirmation
import com.app.nuri.unstuck.SafetyAlert
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Senior-side posting (design §13.1): local records → encrypted
 * Firestore documents. All content travels in the single `payload`
 * field; the documents carry routing metadata only (§5.1).
 *
 * Event ids are deterministic (a hash of the record itself), so a
 * re-post after an offline stretch is an idempotent overwrite,
 * never a duplicate — Firestore's own offline queue plus this
 * makes the posting path safe to retry blindly.
 *
 * The [NoOpRemoteObservationRepository] is the default everywhere:
 * Independent mode, no binding, or no project configured — in all
 * those states this feature does not exist (§1), and the fake in
 * tests verifies zero store calls.
 */
interface RemoteObservationRepository {
    suspend fun postCheckIn(record: CheckInRecord)
    suspend fun postMedConfirmation(record: MedConfirmation)
    suspend fun postMedMissed(entryName: String, dayKey: String, timeText: String)
    suspend fun postDiaryEntry(entry: DiaryEntry)
    suspend fun postSafetyAlert(alert: SafetyAlert, locationText: String? = null)
    suspend fun postCallEvent(payload: CallEventPayload)
    suspend fun postStatus(status: RemoteStatus)
    suspend fun postShadowAck(ack: ShadowAckPayload)
    fun observeShadow(onChange: (version: Int?, payload: ShadowPayload?) -> Unit): AutoCloseable
}

object NoOpRemoteObservationRepository : RemoteObservationRepository {
    override suspend fun postCheckIn(record: CheckInRecord) {}
    override suspend fun postMedConfirmation(record: MedConfirmation) {}
    override suspend fun postMedMissed(entryName: String, dayKey: String, timeText: String) {}
    override suspend fun postDiaryEntry(entry: DiaryEntry) {}
    override suspend fun postSafetyAlert(alert: SafetyAlert, locationText: String?) {}
    override suspend fun postCallEvent(payload: CallEventPayload) {}
    override suspend fun postStatus(status: RemoteStatus) {}
    override suspend fun postShadowAck(ack: ShadowAckPayload) {}
    override fun observeShadow(onChange: (Int?, ShadowPayload?) -> Unit): AutoCloseable =
        AutoCloseable { }
}

/** Deterministic event ids (idempotent re-posts). */
object RemoteEventIds {
    fun of(vararg parts: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
}

class FirestoreRemoteObservationRepository(
    private val store: RemoteStore,
    private val paths: HouseholdPaths,
    private val householdKey: () -> ByteArray?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : RemoteObservationRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun cipher(): ByteArray? = householdKey()

    private suspend fun postEvent(
        id: String,
        type: RemoteEventType,
        dayKey: String,
        payloadJson: String,
    ) {
        val key = cipher() ?: return
        store.set(
            paths.event(id),
            mapOf(
                "type" to type.name,
                "dayKey" to dayKey,
                "createdAt" to nowMs(),
                "ackByCaregiver" to false,
                "payload" to PayloadCipher.encryptText(key, payloadJson),
            ),
        )
    }

    override suspend fun postCheckIn(record: CheckInRecord) {
        val payload = CheckInPayload(record.dayKey, record.timeText, record.sleep, record.mood, record.appetite)
        postEvent(
            RemoteEventIds.of("CHECKIN", record.dayKey, record.timeText),
            RemoteEventType.CHECKIN, record.dayKey,
            json.encodeToString(CheckInPayload.serializer(), payload),
        )
    }

    override suspend fun postMedConfirmation(record: MedConfirmation) {
        val payload = MedEventPayload(record.dayKey, record.timeText, record.entryName, taken = true)
        postEvent(
            RemoteEventIds.of("MED_TAKEN", record.dayKey, record.timeText, record.entryName),
            RemoteEventType.MED_TAKEN, record.dayKey,
            json.encodeToString(MedEventPayload.serializer(), payload),
        )
    }

    override suspend fun postMedMissed(entryName: String, dayKey: String, timeText: String) {
        val payload = MedEventPayload(dayKey, timeText, entryName, taken = false)
        postEvent(
            RemoteEventIds.of("MED_MISSED", dayKey, entryName),
            RemoteEventType.MED_MISSED, dayKey,
            json.encodeToString(MedEventPayload.serializer(), payload),
        )
    }

    override suspend fun postDiaryEntry(entry: DiaryEntry) {
        val payload = DiaryPayload(entry.dayKey, entry.timeText, entry.what, entry.outcome)
        postEvent(
            RemoteEventIds.of("DIARY", entry.dayKey, entry.timeText, entry.what),
            RemoteEventType.DIARY, entry.dayKey,
            json.encodeToString(DiaryPayload.serializer(), payload),
        )
    }

    override suspend fun postSafetyAlert(alert: SafetyAlert, locationText: String?) {
        val key = cipher() ?: return
        val payload = AlertPayload(alert.dayKey, alert.timeText, alert.title, alert.detail, locationText)
        store.set(
            paths.alert(RemoteEventIds.of("ALERT", alert.dayKey, alert.timeText, alert.title)),
            mapOf(
                "class" to AlertRouting.alertClassFor(alert.title).name,
                "createdAt" to nowMs(),
                "ackByCaregiver" to false,
                "payload" to PayloadCipher.encryptText(
                    key, json.encodeToString(AlertPayload.serializer(), payload),
                ),
            ),
        )
    }

    override suspend fun postCallEvent(payload: CallEventPayload) {
        postEvent(
            RemoteEventIds.of("CALL", payload.dayKey, payload.timeText, payload.kind, payload.outcome),
            RemoteEventType.CALL, payload.dayKey,
            json.encodeToString(CallEventPayload.serializer(), payload),
        )
    }

    override suspend fun postStatus(status: RemoteStatus) {
        val key = cipher() ?: return
        val payload = status.payload ?: return
        store.set(
            paths.statusToday,
            mapOf(
                "dayKey" to status.dayKey,
                "updatedAt" to RemoteStore.SERVER_TIMESTAMP,
                "lastSeenAt" to RemoteStore.SERVER_TIMESTAMP,
                // Order §B1: plaintext routing metadata (versions are not content).
                "appVersionName" to status.appVersionName,
                "appVersionCode" to status.appVersionCode,
                "lastUpdateCheckText" to status.lastUpdateCheckText,
                "payload" to PayloadCipher.encryptText(
                    key, json.encodeToString(StatusPayload.serializer(), payload),
                ),
            ),
        )
    }

    override suspend fun postShadowAck(ack: ShadowAckPayload) {
        val key = cipher() ?: return
        store.set(
            paths.shadowAck,
            mapOf(
                "appliedVersion" to ack.appliedVersion,
                "appliedAt" to nowMs(),
                "payload" to PayloadCipher.encryptText(
                    key, json.encodeToString(ShadowAckPayload.serializer(), ack),
                ),
            ),
            // Merge: the same document carries the call-signal field
            // (design §17.1) — an ack must never clobber it.
            merge = true,
        )
    }

    override fun observeShadow(onChange: (Int?, ShadowPayload?) -> Unit): AutoCloseable =
        store.listenDocument(paths.shadow) { doc ->
            if (doc == null) {
                onChange(null, null)
            } else {
                val key = cipher()
                val blob = RemoteDoc.str(doc, "payload")
                val payload = if (key != null && blob != null) {
                    try {
                        json.decodeFromString(
                            ShadowPayload.serializer(),
                            PayloadCipher.decryptText(key, blob),
                        )
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                onChange(RemoteDoc.int(doc, "version"), payload)
            }
        }
}
