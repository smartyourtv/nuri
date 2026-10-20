package com.app.nuri.call

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * WebRTC signaling over the two channels the published Firestore
 * rules already allow (design §17.1):
 *
 * - caregiver → senior: fields on the HOUSEHOLD document
 *   (a caregiver update is unrestricted; the senior reads it).
 * - senior → caregiver: a field on `shadowAck/latest`
 *   (senior-writable; caregiver-readable).
 *
 * The initiator writes its OFFER into its own channel; the
 * responder writes its ANSWER into its own channel. Non-trickle
 * ICE: each blob carries the complete SDP (candidates embedded),
 * so exactly one write per side per call. State travels with the
 * blob: RINGING → CONNECTED → ENDED (with the outcome facts).
 *
 * SDP blobs are connection metadata, not conversation content —
 * but they still travel only inside these two documents, between
 * the two bound devices, and are cleared when the call ends.
 */
@Serializable
data class CallSignal(
    val sessionId: String,
    /** CHECKIN or ALERT (CallKind). */
    val kind: String,
    /** VIDEO_AND_AUDIO or VOICE_ONLY (CallMedia name). */
    val media: String,
    /** Non-trickle SDP (offer or answer, by [fromRole]). */
    val sdp: String? = null,
    /** SENIOR or CAREGIVER — who wrote this blob. */
    val fromRole: String,
    /** RINGING / CONNECTED / DECLINED / ENDED / NO_ANSWER */
    val state: String,
    val atMs: Long,
    /** For ENDED: seconds the media was connected. */
    val durationSec: Int = 0,
    val endedBy: String? = null,
)

object CallSignalState {
    const val RINGING = "RINGING"
    const val CONNECTED = "CONNECTED"
    const val DECLINED = "DECLINED"
    const val ENDED = "ENDED"
    const val NO_ANSWER = "NO_ANSWER"
}

/** Field-level codec between a [CallSignal] and document fields. */
object CallSignalCodec {
    const val FIELD = "callSignal"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(signal: CallSignal): Map<String, Any?> =
        mapOf(FIELD to json.encodeToString(CallSignal.serializer(), signal))

    fun decode(doc: Map<String, Any?>?): CallSignal? {
        val raw = doc?.get(FIELD) as? String ?: return null
        return try {
            json.decodeFromString(CallSignal.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    /** The matching signal for [sessionId], or null when the doc carries another/none. */
    fun forSession(doc: Map<String, Any?>?, sessionId: String): CallSignal? =
        decode(doc)?.takeIf { it.sessionId == sessionId }
}
