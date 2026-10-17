package com.app.nuri.update.android

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.app.nuri.update.UpdateSchedulePolicy
import java.util.Calendar

/**
 * Nightly self-update check (brief SS10). JobScheduler already guarantees
 * the charging + unmetered-network constraints declared in [UpdateScheduler];
 * this service adds the night-window preference through
 * [UpdateSchedulePolicy] — the single, unit-tested decision — and runs the
 * executor off the main thread. Outside the window the cycle is simply
 * skipped; the daily period tries again tomorrow.
 */
class NuriUpdateJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        Thread {
            try {
                if (shouldRunNow(app)) {
                    val result = NuriUpdatePlatform.createExecutor(app).runScheduledCheck()
                    NuriUpdatePlatform.runtimeStore(app).recordRunResult(result.detail)
                }
            } catch (_: Exception) {
                // A failed check is a skipped check; nothing was changed and
                // the next period retries. Never crash from a background job.
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private fun shouldRunNow(context: Context): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = battery?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return UpdateSchedulePolicy.shouldRunNow(hour, charging, unmetered)
    }
}
