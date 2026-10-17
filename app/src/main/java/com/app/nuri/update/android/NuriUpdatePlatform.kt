package com.app.nuri.update.android

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.app.nuri.BuildConfig
import com.app.nuri.backup.BackupCrypto
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.update.SelfUpdateExecutor
import com.app.nuri.update.SelfUpdatePolicy
import com.app.nuri.update.UpdateSelfTestGateway
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Wiring for the self-update executor (brief SS10): builds the executor with
 * the Android gateways, schedules the nightly job, and runs the post-update
 * check at app start. Policy inputs come from the same places the Settings
 * screen reads, so what Settings shows and what the executor obeys can never
 * drift apart.
 */
object NuriUpdatePlatform {

    fun runtimeStore(context: Context): UpdateRuntimeStore =
        UpdateRuntimeStore(context.applicationContext)

    fun createExecutor(context: Context): SelfUpdateExecutor {
        val app = context.applicationContext
        val repo = SettingsRepository(app)
        val store = runtimeStore(app)
        val downloadDir = File(app.cacheDir, "updates").apply { mkdirs() }
        return SelfUpdateExecutor(
            autoUpdateEnabled = {
                // Called on the executor's background thread only.
                runBlocking {
                    SelfUpdatePolicy.autoUpdateEnabled(
                        isPrivateFlavor = BuildConfig.FLAVOR == "private",
                        accessMode = repo.accessMode.first(),
                        toggle = repo.autoUpdateToggle.first()
                    )
                }
            },
            feed = HttpUpdateFeedGateway(BuildConfig.UPDATE_FEED_URL),
            downloader = StreamingApkDownloader(),
            inspector = AndroidApkInspector(app),
            installer = PackageInstallerGateway(app),
            selfTest = object : UpdateSelfTestGateway {
                // The build's real self-test today: the same encrypted-backup
                // round-trip the Diagnostics card runs (save/restore match +
                // wrong-passphrase rejection). If it fails, the install is
                // treated as suspect and the caregiver is alerted.
                override fun runSelfTest(): Boolean = BackupCrypto.selfTest().ok
            },
            alerts = AndroidUpdateAlerts(app, store),
            pendingStore = store,
            downloadDir = downloadDir
        )
    }
}

/**
 * The scheduled background check (brief SS10): a persisted daily job whose
 * constraints (charging + unmetered network) JobScheduler enforces; the job
 * service adds the night-window preference via [com.app.nuri.update.UpdateSchedulePolicy].
 */
object UpdateScheduler {
    private const val JOB_ID = 4101

    fun schedule(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, NuriUpdateJobService::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(24))
            .setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPersisted(true)
            .build()
        scheduler.schedule(info)
    }
}

/**
 * Post-update resolution at app start: if an update was committed and the
 * app has since been restarted into the new version, the executor runs the
 * self-test, leaves the quiet after-note, or alerts the caregiver.
 */
object PostUpdateCheck {
    fun runAsync(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                val result = NuriUpdatePlatform.createExecutor(app).checkAfterLaunch()
                if (result != null) {
                    NuriUpdatePlatform.runtimeStore(app).recordRunResult(result.detail)
                }
            } catch (_: Exception) {
                // A failed post-update check must never block app start; the
                // pending marker survives and the next start retries.
            }
        }.start()
    }
}
