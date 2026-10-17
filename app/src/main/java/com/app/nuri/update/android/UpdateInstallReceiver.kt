package com.app.nuri.update.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives PackageInstaller session status for self-updates (brief SS10).
 *
 * - STATUS_PENDING_USER_ACTION: the device did not honor user-action-not-
 *   required, so surface the system installer prompt — the one-tap fallback.
 * - STATUS_SUCCESS: the install landed; the executor's next-launch check
 *   runs the self-test and leaves the after-note. Nothing else to do here.
 * - Anything else: the install failed or was abandoned — clear the pending
 *   marker (so the next launch doesn't wait forever) and alert the caregiver.
 *   Nuri keeps running the version it has; there is no rollback to trigger.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PackageInstallerGateway.ACTION_UPDATE_STATUS) return
        val app = context.applicationContext
        val store = UpdateRuntimeStore(app)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        app.startActivity(confirm)
                    } catch (_: Exception) {
                        // The prompt could not be shown; the pending marker
                        // stays and the next launch reports it as still
                        // installing rather than pretending success.
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                store.recordRunResult("The update installed. Nuri will check itself when it starts again.")
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                store.clear()
                AndroidUpdateAlerts(app, store).caregiverAlert(
                    "Nuri update did not install",
                    "The update did not install" + (message?.let { ": $it" } ?: "") +
                        ". Nuri keeps running the version it has."
                )
            }
        }
    }
}
