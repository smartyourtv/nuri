package com.app.nuri.remote.android

import com.app.nuri.remote.RemoteStore
import com.app.nuri.remote.RemoteTransaction
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [RemoteStore] over Cloud Firestore. Boundary conversions:
 * Firestore Timestamps become epoch millis; the
 * [RemoteStore.SERVER_TIMESTAMP] sentinel becomes a server
 * timestamp write. The Firestore SDK's offline persistence is
 * enabled by the session layer, so writes queue on-device and
 * sync on reconnect (design §7) without this class doing anything.
 */
class FirestoreRemoteStore(
    private val firestore: FirebaseFirestore,
    private val onError: (String, Exception) -> Unit = { _, _ -> },
) : RemoteStore {

    private fun doc(path: String): DocumentReference = firestore.document(path)
    private fun col(path: String) = firestore.collection(path)

    private fun toFirestore(value: Any?): Any? = when (value) {
        RemoteStore.SERVER_TIMESTAMP -> FieldValue.serverTimestamp()
        is com.app.nuri.remote.RemoteTimestamp ->
            Timestamp(value.epochMs / 1000, ((value.epochMs % 1000) * 1_000_000).toInt())
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to toFirestore(v) }
        is List<*> -> value.map { toFirestore(it) }
        is Int -> value.toLong()
        else -> value
    }

    private fun fromFirestore(value: Any?): Any? = when (value) {
        is Timestamp -> com.app.nuri.remote.RemoteTimestamp(value.seconds * 1000 + value.nanoseconds / 1_000_000)
        is Date -> value.time
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to fromFirestore(v) }
        is List<*> -> value.map { fromFirestore(it) }
        else -> value
    }

    private fun fromDoc(data: Map<String, Any>?): Map<String, Any?>? =
        data?.mapValues { (_, v) -> fromFirestore(v) }

    override suspend fun get(path: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        val snap = Tasks.await(doc(path).get())
        if (snap.exists()) fromDoc(snap.data) else null
    }

    override suspend fun list(collectionPath: String): List<Pair<String, Map<String, Any?>>> =
        withContext(Dispatchers.IO) {
            val snap = Tasks.await(col(collectionPath).get())
            snap.documents.map { it.id to (fromDoc(it.data) ?: emptyMap()) }
        }

    override suspend fun set(path: String, data: Map<String, Any?>, merge: Boolean) {
        withContext(Dispatchers.IO) {
            // Nulls in a non-merge write mean "absent"; in a merge
            // write they mean "clear the field".
            val prepared = LinkedHashMap<String, Any>()
            for ((k, v) in data) {
                when {
                    v != null -> prepared[k] = toFirestore(v)!!
                    merge -> prepared[k] = FieldValue.delete()
                }
            }
            val task = if (merge) {
                doc(path).set(prepared, SetOptions.merge())
            } else {
                doc(path).set(prepared)
            }
            Tasks.await(task)
        }
    }

    override suspend fun update(path: String, fields: Map<String, Any?>) {
        withContext(Dispatchers.IO) {
            val prepared = LinkedHashMap<String, Any>()
            for ((k, v) in fields) {
                prepared[k] = if (v == null) FieldValue.delete() else toFirestore(v)!!
            }
            Tasks.await(doc(path).update(prepared))
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { Tasks.await(doc(path).delete()) }
    }

    override suspend fun <T> transaction(block: (RemoteTransaction) -> T): T =
        withContext(Dispatchers.IO) {
            Tasks.await(
                firestore.runTransaction { tx ->
                    val wrapped = object : RemoteTransaction {
                        override fun get(path: String): Map<String, Any?>? {
                            val snap = tx.get(doc(path))
                            return if (snap.exists()) fromDoc(snap.data) else null
                        }

                        override fun set(path: String, data: Map<String, Any?>, merge: Boolean) {
                            val prepared = LinkedHashMap<String, Any>()
                            for ((k, v) in data) {
                                when {
                                    v != null -> prepared[k] = toFirestore(v)!!
                                    merge -> prepared[k] = FieldValue.delete()
                                }
                            }
                            if (merge) tx.set(doc(path), prepared, SetOptions.merge())
                            else tx.set(doc(path), prepared)
                        }

                        override fun update(path: String, fields: Map<String, Any?>) {
                            val prepared = LinkedHashMap<String, Any>()
                            for ((k, v) in fields) {
                                prepared[k] = if (v == null) FieldValue.delete() else toFirestore(v)!!
                            }
                            tx.update(doc(path), prepared)
                        }

                        override fun delete(path: String) {
                            tx.delete(doc(path))
                        }
                    }
                    block(wrapped)
                },
            )
        }

    override fun listenDocument(
        path: String,
        onChange: (Map<String, Any?>?) -> Unit,
    ): AutoCloseable {
        val registration: ListenerRegistration = doc(path).addSnapshotListener { snap, error ->
            if (error != null) {
                onError("listenDocument:$path", error)
                return@addSnapshotListener
            }
            onChange(if (snap != null && snap.exists()) fromDoc(snap.data) else null)
        }
        return AutoCloseable { registration.remove() }
    }

    override fun listenCollection(
        path: String,
        onChange: (List<Pair<String, Map<String, Any?>>>) -> Unit,
    ): AutoCloseable {
        val registration: ListenerRegistration = col(path).addSnapshotListener { snap, error ->
            if (error != null) {
                onError("listenCollection:$path", error)
                return@addSnapshotListener
            }
            onChange(snap?.documents?.map { it.id to (fromDoc(it.data) ?: emptyMap()) } ?: emptyList())
        }
        return AutoCloseable { registration.remove() }
    }
}
