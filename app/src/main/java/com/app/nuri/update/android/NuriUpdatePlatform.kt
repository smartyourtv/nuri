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
 * The fallback ladder for the nightly job (pure — unit-tested). Each step
 * relaxes exactly one requirement; a step is tried only when every step
 * before it failed (threw, or JobScheduler returned RESULT_FAILURE —
 * which the pre-v0.3.1 code silently ignored).
 */
object UpdateSchedulePlan {
    data class Step(val name: String, val persisted: Boolean, val requiresCharging: Boolean, val unmeteredOnly: Boolean)

    val STEPS: List<Step> = listOf(
        Step("full (persisted, charging, unmetered)", persisted = true, requiresCharging = true, unmeteredOnly = true),
        Step("no charging requirement (persisted, unmetered)", persisted = true, requiresCharging = false, unmeteredOnly = true),
        Step("minimal (persisted, any network)", persisted = true, requiresCharging = false, unmeteredOnly = false),
        Step("session-only (not persisted, any network)", persisted = false, requiresCharging = false, unmeteredOnly = false),
    )

    /** A stable signature for a scheduling failure, for report dedupe. */
    fun failureSignature(stepName: String, errorClass: String?): String =
        "sched:$stepName:${errorClass ?: "result-failure"}"
}

/**
 * The scheduled background check (brief SS10): a persisted daily job whose
 * constraints (charging + unmetered network) JobScheduler enforces; the job
 * service adds the night-window preference via [com.app.nuri.update.UpdateSchedulePolicy].
 *
 * v0.3.1 DEFENSIVE REWRITE (Shawn's phone, 2026-10-18: schedule() failed at
 * startup AND self-repair "could not fix it"): the old code ignored
 * `JobScheduler.schedule()`'s return code (RESULT_FAILURE is silent),
 * never validated the service component, and let any exception escape to
 * the caller. Now: the component is resolved through PackageManager
 * first, each ladder step is attempted in its own catch, the return code
 * is checked, success is verified by re-reading pending jobs, and a total
 * failure is recorded ONCE per distinct failure signature with the full
 * step-by-step evidence (exception class+message per step) — so the next
 * device report is decisive instead of repetitive. This function never
 * throws.
 */
object UpdateScheduler {
    private const val JOB_ID = 4101

    /** Whether the nightly job is currently scheduled (self-repair reconcile reads this). */
    fun isScheduled(context: Context): Boolean {
        val scheduler = context.applicationContext
            .getSystemService(JobScheduler::class.java) ?: return false
        return runCatching { scheduler.allPendingJobs.any { it.id == JOB_ID } }.getOrDefault(false)
    }

    /**
     * Try to schedule the nightly job. Returns true when the job is
     * pending afterwards (already scheduled counts). Never throws.
     */
    fun schedule(context: Context): Boolean {
        val app = context.applicationContext
        val store = UpdateRuntimeStore(app)
        val evidence = StringBuilder()
        try {
            val scheduler = app.getSystemService(JobScheduler::class.java)
            if (scheduler == null) {
                evidence.append("JobScheduler service was not available on this phone. ")
                recordFailureOnce(store, "no-service", null, evidence.toString())
                return false
            }
            if (runCatching { scheduler.allPendingJobs.any { it.id == JOB_ID } }.getOrDefault(false)) {
                store.clearScheduleFailureSignature()
                return true
            }
            val component = ComponentName(app, NuriUpdateJobService::class.java)
            // Validate the component BEFORE asking JobScheduler to use it:
            // an undeclared/mismatched service is the classic scheduler
            // IllegalArgumentException, and naming it beats guessing.
            val resolved = runCatching {
                app.packageManager.getServiceInfo(component, 0)
            }.getOrNull()
            if (resolved == null) {
                evidence.append("The update job service (${component.className}) is NOT declared on this phone's installed build — JobScheduler cannot use it. ")
                recordFailureOnce(store, "component-missing", null, evidence.toString())
                return false
            }
            var lastSignature = "none"
            for (step in UpdateSchedulePlan.STEPS) {
                val outcome = runCatching {
                    val builder = JobInfo.Builder(JOB_ID, component)
                        .setPeriodic(TimeUnit.HOURS.toMillis(24))
                        .setPersisted(step.persisted)
                    if (step.requiresCharging) builder.setRequiresCharging(true)
                    if (step.unmeteredOnly) builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    scheduler.schedule(builder.build())
                }
                val resultCode = outcome.getOrNull()
                when {
                    outcome.isFailure -> {
                        val e = outcome.exceptionOrNull()
                        evidence.append("Step '${step.name}' threw ${e?.javaClass?.name}: ${e?.message ?: "no message"}. ")
                        lastSignature = UpdateSchedulePlan.failureSignature(step.name, e?.javaClass?.simpleName)
                    }
                    resultCode == JobScheduler.RESULT_SUCCESS -> {
                        // Verify rather than trust the return code.
                        if (isScheduled(app)) {
                            store.clearScheduleFailureSignature()
                            return true
                        }
                        evidence.append("Step '${step.name}' reported success but the job is not pending afterwards. ")
                        lastSignature = UpdateSchedulePlan.failureSignature(step.name, "not-pending-after-success")
                    }
                    else -> {
                        evidence.append("Step '${step.name}' was refused by JobScheduler (result $resultCode). ")
                        lastSignature = UpdateSchedulePlan.failureSignature(step.name, null)
                    }
                }
            }
            recordFailureOnce(store, lastSignature, null, evidence.toString())
            return false
        } catch (t: Throwable) {
            // Last-resort guard: scheduling must never crash app startup.
            runCatching {
                recordFailureOnce(store, "unexpected", t.javaClass.simpleName,
                    "Unexpected failure while scheduling: ${t.javaClass.name}: ${t.message ?: "no message"}. " + evidence.toString())
            }
            return false
        }
    }

    /**
     * The in-app fallback leg (v0.3.1): if the nightly job could not be
     * scheduled, opening Nuri runs the check itself — at most once per
     * ~20 hours, and only inside the same night/charging/unmetered
     * window the job would have used, so behavior (and honesty about a
     * blank feed) is identical. Runs on its own thread; never throws.
     */
    fun launchFallbackCheckIfDue(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                if (isScheduled(app)) return@Thread
                val store = UpdateRuntimeStore(app)
                val now = System.currentTimeMillis()
                if (now - store.lastCheckAttemptMillis() < TimeUnit.HOURS.toMillis(20)) return@Thread
                if (!UpdateWindowProbe.shouldRunNow(app)) return@Thread
                store.markCheckAttempt(now)
                val result = NuriUpdatePlatform.createExecutor(app).runScheduledCheck()
                store.recordRunResult(result.detail)
            } catch (_: Throwable) {
                // The fallback is a courtesy path; its failure changes
                // nothing and the next launch retries.
            }
        }.start()
    }

    /**
     * Record a scheduling failure once per distinct signature: the same
     * known failure on every launch must not fill the report list.
     */
    private fun recordFailureOnce(store: UpdateRuntimeStore, signature: String, errorClass: String?, evidence: String) {
        if (store.scheduleFailureSignature() == signature) return
        store.setScheduleFailureSignature(signature)
        com.app.nuri.reports.android.CrashReports.recordProblem(
            "Nightly update check could not be scheduled",
            "Ilona could not schedule its nightly update check. Failure signature: $signature. " +
                "Evidence: $evidence" +
                "Until this is fixed, update checks happen when Ilona is opened instead. " +
                "This report is written once for this exact failure; it will not repeat on every start."
        )
    }
}

/**
 * The night-window probe (hour + charging + unmetered), shared by the
 * launch fallback check. NuriUpdateJobService keeps its own copy from
 * vc3; the two must agree, and both defer to
 * [com.app.nuri.update.UpdateSchedulePolicy] for the decision itself.
 */
internal object UpdateWindowProbe {
    fun shouldRunNow(context: Context): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val charging = battery?.let {
            val status = it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val unmetered = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return com.app.nuri.update.UpdateSchedulePolicy.shouldRunNow(hour, charging, unmetered)
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
                    // Self-repair rule (2026-10-17): a failed post-update
                    // self-check is a repair failure — the caregiver alert
                    // above stands, and the evidence also lands in the
                    // crash/error report store.
                    if (result.outcome == com.app.nuri.update.PostUpdateOutcome.UPDATED_SELF_TEST_FAILED) {
                        com.app.nuri.reports.android.CrashReports.recordRepair(
                            what = "Update self-check",
                            evidence = result.detail,
                            status = com.app.nuri.reports.RepairStatus.REPAIR_FAILED
                        )
                    }
                }
            } catch (e: Exception) {
                // A failed post-update check must never block app start; the
                // pending marker survives and the next start retries. The
                // failure is recorded as an error report (2026-10-17 rule).
                com.app.nuri.reports.android.CrashReports.recordError("Post-update check", e)
            }
        }.start()
    }
}
