package com.app.nuri.remote.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.nuri.call.CallCoordinator
import com.app.nuri.call.CallSignalCodec
import com.app.nuri.remote.MissingCheckIn
import com.app.nuri.remote.NoAnswerLadder
import com.app.nuri.remote.RemoteAlertClass
import com.app.nuri.remote.RemoteDoc
import com.app.nuri.remote.ShadowSync
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.reports.android.CrashReports
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The caregiver-side watch service (design §10.1): while running,
 * Firestore listeners on this phone turn new alerts into local
 * notifications within seconds — the Spark-plan substitute for
 * push, with its limits stated honestly in the panel (a phone that
 * kills background apps can delay this until Ilona is opened; the
 * dashboard always shows when information actually arrived, §7).
 *
 * It also runs the two caregiver-side computations: the missing
 * morning check-in (§8.3) and the no-answer ladder (§17.4), and it
 * keeps the shadow document's state field truthful (PENDING →
 * APPLIED when the senior phone's ack names the version).
 */
class CaregiverWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: RemoteSession? = null
    private val closables = mutableListOf<AutoCloseable>()
    private val seenAlertIds = mutableSetOf<String>()
    private var firstAlertSnapshot = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        WatchState.running = true
        WatchState.startedAtMs = System.currentTimeMillis()
        scope.launch { runCatching { attach() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        WatchState.running = false
        closables.forEach { runCatching { it.close() } }
        closables.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watching", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Lets you know when something on his phone needs your attention."
            },
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Ilona is watching")
            .setContentText("You'll get a note here when something on his phone needs you.")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun attach() {
        val repo = SettingsRepository(applicationContext)
        val config = FirebaseInit.bundledConfig(applicationContext)
            ?: FirebaseInit.decodeStoredConfig(repo.remoteConfigJson.first())
            ?: return
        val testOn = repo.remoteTestOn.first()
        val session = if (testOn) {
            val hid = repo.remoteTestHouseholdId.first()
            if (hid.isBlank()) return
            FirebaseInit.session(
                applicationContext, config, com.app.nuri.remote.TestHouseholdPlan.TEST_APP_NAME,
                hid, "CAREGIVER", test = true,
            )
        } else {
            val hid = repo.remoteHouseholdId.first()
            val role = repo.remoteRole.first()
            if (hid.isBlank() || role != "CAREGIVER") return
            FirebaseInit.session(applicationContext, config, null, hid, "CAREGIVER", test = false)
        } ?: return
        this.session = session
        CallCoordinator.attachCaregiverSession(applicationContext, session)
        val remote = session.caregiverRepository

        closables += remote.observeAlerts { alerts ->
            val fresh = if (firstAlertSnapshot) {
                firstAlertSnapshot = false
                alerts.forEach { seenAlertIds += it.id }
                emptyList()
            } else {
                alerts.filter { it.id !in seenAlertIds && !it.ackByCaregiver }
                    .also { list -> list.forEach { seenAlertIds += it.id } }
            }
            for (alert in fresh) {
                notifyAlert(alert.payload.title, alert.payload.detail)
            }
            scope.launch { repo.setRemoteLastSyncAt(System.currentTimeMillis()) }
        }
        closables += remote.observeStatus { status ->
            scope.launch {
                repo.setRemoteLastSyncAt(System.currentTimeMillis())
                if (status != null) evaluateMissingCheckIn(repo, status)
            }
        }
        closables += remote.observeEvents { events ->
            scope.launch { evaluateLadder(repo, events) }
        }
        // Shadow state truthfulness: ack names the version → APPLIED.
        closables += remote.observeShadowAck { ack ->
            if (ack == null) return@observeShadowAck
            scope.launch {
                runCatching {
                    val shadow = session.store.get(session.paths.shadow)
                    val version = RemoteDoc.int(shadow ?: emptyMap(), "version")
                    if (version != null && ack.appliedVersion >= version) {
                        session.store.update(
                            session.paths.shadow,
                            mapOf("state" to ShadowSync.STATE_APPLIED),
                        )
                    }
                }
            }
        }
        // Senior call signals (answers, declines, rings) for the coordinator.
        closables += session.store.listenDocument(session.paths.shadowAck) { doc ->
            CallSignalCodec.decode(doc)?.let { signal ->
                CallCoordinator.onRemoteSignal(applicationContext, signal)
            }
        }
        // Daily pruning (§6), once per attach.
        scope.launch { runCatching { remote.pruneOld() } }
    }

    private suspend fun evaluateMissingCheckIn(repo: SettingsRepository, status: com.app.nuri.remote.RemoteStatus) {
        val cutoffHour = repo.remoteCheckinHour.first()
        val prefs = getSharedPreferences("watch_state", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val calendar = Calendar.getInstance()
        val decision = MissingCheckIn.shouldRaise(
            nowMinutesOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
            cutoffHour = cutoffHour,
            todayKey = today,
            checkInDayKey = status.payload?.checkIn?.dayKey,
            alreadyRaisedForDayKey = prefs.getString("missing_notified_day", null),
        )
        if (decision) {
            prefs.edit().putString("missing_notified_day", today).apply()
            notifyAlert("No morning check-in yet", MissingCheckIn.LINE + " Last contact: " +
                (status.lastSeenAtMs?.let { com.app.nuri.remote.RemoteFreshness.timeText(it) } ?: "none yet today."))
        }
    }

    private suspend fun evaluateLadder(repo: SettingsRepository, events: List<com.app.nuri.remote.RemoteEvent>) {
        val threshold = repo.remoteNoAnswerThreshold.first()
        val raisedAt = repo.remoteLastWelfareRaisedAt.first().takeIf { it > 0 }
        if (NoAnswerLadder.shouldRaiseWelfare(events, System.currentTimeMillis(), threshold, raisedAt)) {
            repo.setRemoteLastWelfareRaisedAt(System.currentTimeMillis())
            notifyAlert(
                "He hasn't answered Ilona's calls",
                "That's ${threshold} unanswered check-in calls within an hour. The panel has the next steps.",
            )
        }
    }

    private fun notifyAlert(title: String, detail: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "His phone's alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Safety alerts and medicine misses from his phone."
            },
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify((title + detail).hashCode(), notification)
        } catch (_: SecurityException) {
            CrashReports.recordProblem("Watch service", "Watch notification could not be posted (permission).")
        }
    }

    companion object {
        const val CHANNEL_ID = "ilona_watch"
        const val ALERT_CHANNEL_ID = "ilona_watch_alerts"
        const val NOTIFICATION_ID = 4301

        fun start(context: Context) {
            val intent = Intent(context, CaregiverWatchService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaregiverWatchService::class.java))
            WatchState.running = false
        }

        /** One-tap restart for the troubleshooting panel (order §B1). */
        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }
}

/** B1 panel state: is the watch service actually running right now? */
object WatchState {
    @Volatile var running: Boolean = false
    @Volatile var startedAtMs: Long = 0L
}
