package com.app.nuri.remote.android

import android.content.Context
import com.app.nuri.remote.FirebaseProjectConfig
import com.app.nuri.remote.HouseholdPaths
import com.app.nuri.remote.RemoteCaregiverRepository
import com.app.nuri.remote.RemoteObservationRepository
import com.app.nuri.remote.FirestoreRemoteObservationRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** One live binding: an authenticated app + its store and key custody. */
class RemoteSession(
    val app: FirebaseApp,
    val uid: String,
    val store: FirestoreRemoteStore,
    val firestore: FirebaseFirestore,
    val paths: HouseholdPaths,
    val keyStore: HouseholdKeyStore,
) {
    val observationRepository: RemoteObservationRepository by lazy {
        FirestoreRemoteObservationRepository(store, paths, { keyStore.householdKey() })
    }
    val caregiverRepository: RemoteCaregiverRepository by lazy {
        RemoteCaregiverRepository(store, paths, { keyStore.householdKey() })
    }
}

/**
 * Firebase bootstrap WITHOUT the google-services Gradle plugin
 * (design §13.1): options are built in code from a
 * [FirebaseProjectConfig] — the private flavor's bundled asset, or
 * the public flavor's pasted config (§10.2). Until a config exists
 * and a binding exists, nothing here runs: the SDK stays inert
 * (§3.3/§10) and Independent mode never reaches this object.
 *
 * Test mode (§11) uses a SECOND, named FirebaseApp so one phone
 * holds two anonymous identities at once; the two identities are
 * separate accounts in the same project (§11.1 note) and must
 * never be merged or linked.
 */
object FirebaseInit {

    private val json = Json { ignoreUnknownKeys = true }

    /** The bundled project config asset — present ONLY in the private flavor. */
    fun bundledConfig(context: Context): FirebaseProjectConfig? = try {
        val text = context.assets.open("google-services.json").bufferedReader().readText()
        FirebaseProjectConfig.fromGoogleServicesJson(text)
    } catch (_: Exception) {
        null
    }

    /** Decode a stored pasted-config JSON (public flavor §10.2). */
    fun decodeStoredConfig(text: String): FirebaseProjectConfig? =
        if (text.isBlank()) {
            null
        } else {
            try {
                json.decodeFromString(FirebaseProjectConfig.serializer(), text)
            } catch (_: Exception) {
                null
            }
        }

    fun encodeStoredConfig(config: FirebaseProjectConfig): String =
        json.encodeToString(FirebaseProjectConfig.serializer(), config)

    /** Get-or-initialize an app instance for [config] ([name] null = default app). */
    @Synchronized
    fun app(context: Context, config: FirebaseProjectConfig, name: String?): FirebaseApp {
        val existing = try {
            if (name == null) FirebaseApp.getInstance() else FirebaseApp.getInstance(name)
        } catch (_: IllegalStateException) {
            null
        }
        if (existing != null) return existing
        val options = FirebaseOptions.Builder()
            .setProjectId(config.projectId)
            .setApiKey(config.apiKey)
            .setApplicationId(config.appId)
            .setGcmSenderId(config.senderId)
            .build()
        return if (name == null) {
            FirebaseApp.initializeApp(context, options)
        } else {
            FirebaseApp.initializeApp(context, options, name)
        }
    }

    /**
     * Open a session: initialize the app and sign in anonymously.
     * The anonymous UID IS the device identity (§4.1); it persists
     * across restarts because auto-clean-up is OFF in the console
     * (firebase-setup-status.md). Returns null when sign-in fails
     * (offline first run, project unreachable) — callers treat null
     * as "not connected yet" and retry on next launch/action.
     */
    suspend fun session(
        context: Context,
        config: FirebaseProjectConfig,
        appName: String?,
        householdId: String,
        role: String,
        test: Boolean,
        onError: (String, Exception) -> Unit = { _, _ -> },
    ): RemoteSession? = withContext(Dispatchers.IO) {
        try {
            val app = app(context, config, appName)
            val auth = FirebaseAuth.getInstance(app)
            val uid = auth.currentUser?.uid ?: Tasks.await(auth.signInAnonymously()).user?.uid
            if (uid == null) return@withContext null
            val firestore = FirebaseFirestore.getInstance(app)
            val store = FirestoreRemoteStore(firestore, onError)
            RemoteSession(
                app = app,
                uid = uid,
                store = store,
                firestore = firestore,
                paths = HouseholdPaths(householdId),
                keyStore = HouseholdKeyStore(context, HouseholdKeyStore.tagFor(householdId, role, test)),
            )
        } catch (e: Exception) {
            onError("session", e)
            null
        }
    }
}
