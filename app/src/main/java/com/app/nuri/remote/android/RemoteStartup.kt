package com.app.nuri.remote.android

import android.content.Context
import com.app.nuri.config.NuriSettings
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App-start wiring for watching (design §13.1): resolve this
 * phone's binding and start the matching side —
 *
 * - senior binding (or test mode): the sync engine mirrors records,
 *   keeps status fresh, applies shadow, and feeds call triggers.
 * - caregiver binding: the watch service runs (alerts → local
 *   notifications, §10.1) when watching is enabled.
 *
 * Independent / unbound phones start nothing. All failures are
 * recorded as reports, never crashes (startup law).
 */
object RemoteStartup {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun startAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { start(app) }
                .onFailure { CrashReports.recordError("Remote observation startup", it) }
        }
    }

    private suspend fun start(app: Context) {
        if (started) return
        val repo = SettingsRepository(app)
        val testOn = repo.remoteTestOn.first()
        val role = repo.remoteRole.first()
        val mode = repo.accessMode.first()
        if (testOn) {
            // Test mode (§11): this phone runs BOTH sides.
            RemoteSessions.seniorSession(app, repo)?.let { session ->
                started = true
                RemoteSyncEngine(app, repo, session, scope).start()
            }
            if (repo.remoteWatchEnabled.first()) {
                CaregiverWatchService.start(app)
            }
            return
        }
        if (mode == NuriSettings.AccessMode.INDEPENDENT) return
        when (role) {
            "SENIOR" -> {
                RemoteSessions.seniorSession(app, repo)?.let { session ->
                    started = true
                    RemoteSyncEngine(app, repo, session, scope).start()
                }
            }
            "CAREGIVER" -> {
                RemoteSessions.caregiverSession(app, repo)?.let { session ->
                    com.app.nuri.call.CallCoordinator.attachCaregiverSession(app, session)
                }
                if (repo.remoteWatchEnabled.first()) {
                    CaregiverWatchService.start(app)
                }
            }
        }
    }
}
