package com.app.nuri.remote

/**
 * In-memory [RemoteStore] for JVM tests: documents are maps in a
 * flat path→data table; listeners fire synchronously on writes;
 * transactions run the block directly (single-threaded tests).
 * Null values in a write DELETE the key (mirrors the Firestore
 * implementation's merge semantics closely enough for protocol
 * tests; non-merge set drops nulls).
 */
class FakeRemoteStore : RemoteStore {
    val docs = mutableMapOf<String, MutableMap<String, Any?>>()
    var writeCount = 0
        private set
    private val docListeners = mutableMapOf<String, MutableList<(Map<String, Any?>?) -> Unit>>()
    private val colListeners = mutableMapOf<String, MutableList<(List<Pair<String, Map<String, Any?>>>) -> Unit>>()

    private fun notifyDoc(path: String) {
        docListeners[path]?.forEach { it(docs[path]?.toMap()) }
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) notifyCol(parent)
    }

    private fun notifyCol(path: String) {
        colListeners[path]?.forEach { listener ->
            listener(
                docs.entries.filter { it.key.startsWith("$path/") && it.key.removePrefix("$path/").count { c -> c == '/' } == 0 }
                    .map { it.key.removePrefix("$path/") to it.value.toMap() },
            )
        }
    }

    override suspend fun get(path: String): Map<String, Any?>? = docs[path]?.toMap()

    override suspend fun list(collectionPath: String): List<Pair<String, Map<String, Any?>>> =
        docs.entries.filter { it.key.startsWith("$collectionPath/") }
            .map { it.key.removePrefix("$collectionPath/") to it.value.toMap() }

    override suspend fun set(path: String, data: Map<String, Any?>, merge: Boolean) {
        writeCount++
        val target = if (merge) docs.getOrPut(path) { mutableMapOf() } else mutableMapOf()
        for ((k, v) in data) {
            if (v == null) {
                if (merge) target.remove(k)
            } else {
                target[k] = v
            }
        }
        if (!merge) docs[path] = target
        notifyDoc(path)
    }

    override suspend fun update(path: String, fields: Map<String, Any?>) {
        writeCount++
        val target = docs.getOrPut(path) { mutableMapOf() }
        for ((k, v) in fields) {
            if (v == null) target.remove(k) else target[k] = v
        }
        notifyDoc(path)
    }

    override suspend fun delete(path: String) {
        writeCount++
        docs.remove(path)
        notifyDoc(path)
    }

    override suspend fun <T> transaction(block: (RemoteTransaction) -> T): T {
        val tx = object : RemoteTransaction {
            override fun get(path: String): Map<String, Any?>? = docs[path]?.toMap()
            override fun set(path: String, data: Map<String, Any?>, merge: Boolean) {
                val target = if (merge) docs.getOrPut(path) { mutableMapOf() } else mutableMapOf()
                for ((k, v) in data) {
                    if (v == null) {
                        if (merge) target.remove(k)
                    } else {
                        target[k] = v
                    }
                }
                if (!merge) docs[path] = target
            }

            override fun update(path: String, fields: Map<String, Any?>) {
                val target = docs.getOrPut(path) { mutableMapOf() }
                for ((k, v) in fields) {
                    if (v == null) target.remove(k) else target[k] = v
                }
            }

            override fun delete(path: String) {
                docs.remove(path)
            }
        }
        val result = block(tx)
        // Post-transaction: notify everything touched (coarse but fine).
        docListeners.keys.toList().forEach { notifyDoc(it) }
        return result
    }

    override fun listenDocument(path: String, onChange: (Map<String, Any?>?) -> Unit): AutoCloseable {
        docListeners.getOrPut(path) { mutableListOf() } += onChange
        onChange(docs[path]?.toMap())
        return AutoCloseable { docListeners[path]?.remove(onChange) }
    }

    override fun listenCollection(
        path: String,
        onChange: (List<Pair<String, Map<String, Any?>>>) -> Unit,
    ): AutoCloseable {
        colListeners.getOrPut(path) { mutableListOf() } += onChange
        notifyCol(path)
        return AutoCloseable { colListeners[path]?.remove(onChange) }
    }
}
