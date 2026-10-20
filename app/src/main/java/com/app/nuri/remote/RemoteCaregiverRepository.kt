package com.app.nuri.remote

import kotlinx.serialization.json.Json

/**
 * Caregiver-side access (design §13.1): observe the household's
 * status, events and alerts (decrypting payloads locally — §5.4:
 * nothing server-side can read them), write shadow documents with
 * monotonic versions + PENDING state (§7), observe the ack, and
 * prune records past the 90-day retention (§6).
 */
class RemoteCaregiverRepository(
    private val store: RemoteStore,
    private val paths: HouseholdPaths,
    private val householdKey: () -> ByteArray?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun decrypt(blob: String?): String? {
        val key = householdKey() ?: return null
        if (blob == null) return null
        return try {
            PayloadCipher.decryptText(key, blob)
        } catch (_: Exception) {
            null // tampered/foreign ciphertext renders as absent, never as garbage
        }
    }

    fun decodeEvent(id: String, doc: Map<String, Any?>): RemoteEvent? {
        val type = try {
            RemoteEventType.valueOf(RemoteDoc.str(doc, "type") ?: return null)
        } catch (_: Exception) {
            return null
        }
        val plain = decrypt(RemoteDoc.str(doc, "payload")) ?: return null
        val base = RemoteEvent(
            id = id,
            type = type,
            dayKey = RemoteDoc.str(doc, "dayKey") ?: "",
            createdAtMs = RemoteDoc.long(doc, "createdAt") ?: 0L,
            ackByCaregiver = RemoteDoc.bool(doc, "ackByCaregiver") ?: false,
        )
        return try {
            when (type) {
                RemoteEventType.CHECKIN -> base.copy(
                    checkIn = json.decodeFromString(CheckInPayload.serializer(), plain),
                )
                RemoteEventType.MED_TAKEN, RemoteEventType.MED_MISSED -> base.copy(
                    med = json.decodeFromString(MedEventPayload.serializer(), plain),
                )
                RemoteEventType.DIARY -> base.copy(
                    diary = json.decodeFromString(DiaryPayload.serializer(), plain),
                )
                RemoteEventType.SUMMARY -> base.copy(summaryLine = plain)
                RemoteEventType.CALL -> base.copy(
                    call = json.decodeFromString(CallEventPayload.serializer(), plain),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeAlert(id: String, doc: Map<String, Any?>): RemoteAlert? {
        val plain = decrypt(RemoteDoc.str(doc, "payload")) ?: return null
        val payload = try {
            json.decodeFromString(AlertPayload.serializer(), plain)
        } catch (_: Exception) {
            return null
        }
        val cls = try {
            RemoteAlertClass.valueOf(RemoteDoc.str(doc, "class") ?: "SAFETY")
        } catch (_: Exception) {
            RemoteAlertClass.SAFETY
        }
        return RemoteAlert(
            id = id,
            alertClass = cls,
            createdAtMs = RemoteDoc.long(doc, "createdAt") ?: 0L,
            ackByCaregiver = RemoteDoc.bool(doc, "ackByCaregiver") ?: false,
            payload = payload,
        )
    }

    fun decodeStatus(doc: Map<String, Any?>): RemoteStatus {
        val plain = decrypt(RemoteDoc.str(doc, "payload"))
        val payload = plain?.let {
            try {
                json.decodeFromString(StatusPayload.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }
        return RemoteStatus(
            dayKey = RemoteDoc.str(doc, "dayKey") ?: "",
            updatedAtMs = RemoteDoc.timestampMs(doc, "updatedAt"),
            lastSeenAtMs = RemoteDoc.timestampMs(doc, "lastSeenAt"),
            appVersionName = RemoteDoc.str(doc, "appVersionName"),
            appVersionCode = RemoteDoc.int(doc, "appVersionCode"),
            lastUpdateCheckText = RemoteDoc.str(doc, "lastUpdateCheckText"),
            payload = payload,
        )
    }

    fun observeStatus(onChange: (RemoteStatus?) -> Unit): AutoCloseable =
        store.listenDocument(paths.statusToday) { doc ->
            onChange(doc?.let { decodeStatus(it) })
        }

    fun observeEvents(onChange: (List<RemoteEvent>) -> Unit): AutoCloseable =
        store.listenCollection(paths.events) { docs ->
            onChange(
                docs.mapNotNull { (id, doc) -> decodeEvent(id, doc) }
                    .sortedBy { it.createdAtMs },
            )
        }

    fun observeAlerts(onChange: (List<RemoteAlert>) -> Unit): AutoCloseable =
        store.listenCollection(paths.alerts) { docs ->
            onChange(
                docs.mapNotNull { (id, doc) -> decodeAlert(id, doc) }
                    .sortedBy { it.createdAtMs },
            )
        }

    suspend fun acknowledgeEvent(id: String) {
        store.update(paths.event(id), mapOf("ackByCaregiver" to true))
    }

    suspend fun acknowledgeAlert(id: String) {
        store.update(paths.alert(id), mapOf("ackByCaregiver" to true))
    }

    /** Write a shadow document at the next version, state PENDING (§7). */
    suspend fun writeShadow(values: Map<String, String>, updatedBy: String): Int {
        val key = householdKey() ?: throw IllegalStateException("no household key")
        val current = store.get(paths.shadow)
        val next = ShadowSync.nextVersion(RemoteDoc.int(current ?: emptyMap(), "version"))
        store.set(
            paths.shadow,
            mapOf(
                "version" to next,
                "updatedBy" to updatedBy,
                "updatedAt" to nowMs(),
                "state" to ShadowSync.STATE_PENDING,
                "payload" to PayloadCipher.encryptText(
                    key, json.encodeToString(ShadowPayload.serializer(), ShadowPayload(values)),
                ),
            ),
        )
        return next
    }

    fun observeShadowState(onChange: (version: Int?, state: String?) -> Unit): AutoCloseable =
        store.listenDocument(paths.shadow) { doc ->
            onChange(
                doc?.let { RemoteDoc.int(it, "version") },
                doc?.let { RemoteDoc.str(it, "state") },
            )
        }

    fun observeShadowAck(onChange: (ShadowAckPayload?) -> Unit): AutoCloseable =
        store.listenDocument(paths.shadowAck) { doc ->
            val plain = doc?.let { decrypt(RemoteDoc.str(it, "payload")) }
            onChange(
                plain?.let {
                    try {
                        json.decodeFromString(ShadowAckPayload.serializer(), it)
                    } catch (_: Exception) {
                        null
                    }
                },
            )
        }

    /** §6 retention: delete events/alerts older than [retentionDays]. Returns the count deleted. */
    suspend fun pruneOld(retentionDays: Int = 90): Int {
        var deleted = 0
        for ((id, doc) in store.list(paths.events)) {
            val created = RemoteDoc.long(doc, "createdAt") ?: continue
            if (shouldDelete(created, nowMs(), retentionDays)) {
                store.delete(paths.event(id)); deleted++
            }
        }
        for ((id, doc) in store.list(paths.alerts)) {
            val created = RemoteDoc.long(doc, "createdAt") ?: continue
            if (shouldDelete(created, nowMs(), retentionDays)) {
                store.delete(paths.alert(id)); deleted++
            }
        }
        return deleted
    }

    companion object {
        /** The pure retention rule used by the Android prune pass. */
        fun shouldDelete(createdAtMs: Long, nowMs: Long, retentionDays: Int = 90): Boolean =
            createdAtMs in 1 until (nowMs - retentionDays * 24L * 60 * 60 * 1000)
    }
}
