package com.app.nuri.remote

/**
 * A Firestore Timestamp value crossing the seam. The published
 * rules compare `expiresAt` to `request.time`, so that field must
 * be a true Timestamp, not epoch millis; every other time field
 * in the protocol is a plain number. The Android store converts
 * this to/from com.google.firebase.Timestamp; fakes pass it
 * through untouched.
 */
data class RemoteTimestamp(val epochMs: Long)

/**
 * The backend seam (design §13.1): every Firestore access flows
 * through this interface so all repository/pairing logic is
 * JVM-testable against an in-memory fake. Documents are plain maps;
 * timestamps travel as epoch millis (the Firestore implementation
 * converts Timestamp/server-timestamp values at the boundary).
 *
 * Paths are full document paths, e.g. "households/{id}",
 * "households/{id}/status/today", "households/{id}/events/{eventId}".
 */
interface RemoteTransaction {
    fun get(path: String): Map<String, Any?>?
    fun set(path: String, data: Map<String, Any?>, merge: Boolean = false)
    fun update(path: String, fields: Map<String, Any?>)
    fun delete(path: String)
}

interface RemoteStore {
    suspend fun get(path: String): Map<String, Any?>?

    /** One-shot collection read: (documentId, data) pairs. */
    suspend fun list(collectionPath: String): List<Pair<String, Map<String, Any?>>>
    suspend fun set(path: String, data: Map<String, Any?>, merge: Boolean = false)
    suspend fun update(path: String, fields: Map<String, Any?>)
    suspend fun delete(path: String)

    /** Atomic read-modify-write; [block] may run more than once on contention. */
    suspend fun <T> transaction(block: (RemoteTransaction) -> T): T

    /** Document listener; close the handle to stop. Callbacks arrive off the main thread. */
    fun listenDocument(path: String, onChange: (Map<String, Any?>?) -> Unit): AutoCloseable

    /** Collection listener: (documentId, data) pairs. */
    fun listenCollection(path: String, onChange: (List<Pair<String, Map<String, Any?>>>) -> Unit): AutoCloseable

    companion object {
        /** Sentinel a store implementation replaces with a server timestamp. */
        val SERVER_TIMESTAMP: Any = object {
            override fun toString(): String = "SERVER_TIMESTAMP"
        }
    }
}

/** Document paths for one household (design §6). */
class HouseholdPaths(val householdId: String) {
    val household: String = "households/$householdId"
    val pairing: String = "households/$householdId/pairing/current"
    val statusToday: String = "households/$householdId/status/today"
    fun event(id: String): String = "households/$householdId/events/$id"
    val events: String = "households/$householdId/events"
    fun alert(id: String): String = "households/$householdId/alerts/$id"
    val alerts: String = "households/$householdId/alerts"
    val shadow: String = "households/$householdId/shadow/current"
    val shadowAck: String = "households/$householdId/shadowAck/latest"
}

/** Small map helpers shared by the store implementations and fakes. */
object RemoteDoc {
    fun str(map: Map<String, Any?>?, key: String): String? = map?.get(key) as? String
    fun bool(map: Map<String, Any?>?, key: String): Boolean? = map?.get(key) as? Boolean
    fun long(map: Map<String, Any?>?, key: String): Long? = when (val v = map?.get(key)) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        else -> null
    }
    fun int(map: Map<String, Any?>?, key: String): Int? = long(map, key)?.toInt()

    /** Epoch millis from either a plain number or a [RemoteTimestamp]. */
    fun timestampMs(map: Map<String, Any?>?, key: String): Long? = when (val v = map?.get(key)) {
        is RemoteTimestamp -> v.epochMs
        else -> long(map, key)
    }
}
