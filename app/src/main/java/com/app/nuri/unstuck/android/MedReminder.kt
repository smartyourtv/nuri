package com.app.nuri.unstuck.android

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.app.nuri.R
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.unstuck.MedRecords
import com.app.nuri.unstuck.SafetyAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Medication reminders (sweep #10): an AlarmManager wake-up per scheduled
 * dose posts a big, plain notification. If the dose is still unconfirmed
 * an hour later, a caregiver safety alert is RECORDED (dashboard/diary);
 * remote delivery to a caregiver device doesn't exist in this build and
 * is never claimed (sweep #13 partial).
 *
 * Exact alarms: uses setAndAllowWhileIdle (no SCHEDULE_EXACT_ALARM
 * permission grab); timing may drift minutes under Doze — stated here,
 * not hidden. Reminders re-arm on boot and whenever the schedule changes.
 */
object MedReminder {
    const val CHANNEL_ID = "nuri_med_reminders"
    const val ACTION_DOSE = "com.app.nuri.action.MED_DOSE"
    const val ACTION_MISSED = "com.app.nuri.action.MED_MISSED_CHECK"
    const val EXTRA_NAME = "med_name"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Medicine reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Time-for-medicine reminders from Ilona."
                },
            )
        }
    }

    fun scheduleAll(context: Context, entries: List<com.app.nuri.unstuck.MedScheduleEntry>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        ensureChannel(context)
        entries.forEachIndexed { idx, entry ->
            if (entry.dueHour !in 0..23) return@forEachIndexed
            val trigger = nextOccurrence(entry.dueHour)
            val pi = PendingIntent.getBroadcast(
                context, 10_000 + idx,
                Intent(context, MedReminderReceiver::class.java).setAction(ACTION_DOSE).putExtra(EXTRA_NAME, entry.nameAsWritten),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) }
            val missedPi = PendingIntent.getBroadcast(
                context, 20_000 + idx,
                Intent(context, MedReminderReceiver::class.java).setAction(ACTION_MISSED).putExtra(EXTRA_NAME, entry.nameAsWritten),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger + 60 * 60 * 1000L, missedPi) }
        }
    }

    private fun nextOccurrence(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }
}

class MedReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(MedReminder.EXTRA_NAME) ?: "medicine"
        val appContext = context.applicationContext
        when (intent.action) {
            MedReminder.ACTION_DOSE -> {
                MedReminder.ensureChannel(appContext)
                val nm = appContext.getSystemService(NotificationManager::class.java)
                val openApp = PendingIntent.getActivity(
                    appContext, 0,
                    appContext.packageManager.getLaunchIntentForPackage(appContext.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(appContext, MedReminder.CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Time for your $name")
                    .setContentText("When you've taken it, open Ilona and tap 'I took my medicine'.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build()
                runCatching { nm.notify(name.hashCode(), notification) }
                // Re-arm for tomorrow.
                CoroutineScope(Dispatchers.IO).launch {
                    val repo = SettingsRepository(appContext)
                    MedReminder.scheduleAll(appContext, repo.medSchedule.first())
                }
            }
            MedReminder.ACTION_MISSED -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val repo = SettingsRepository(appContext)
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val confirmed = repo.medConfirmations.first().filter { it.dayKey == today }
                    val entry = repo.medSchedule.first().firstOrNull { it.nameAsWritten.equals(name, ignoreCase = true) }
                    if (entry != null && MedRecords.isMissed(entry, confirmed, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) {
                        val timeText = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
                        repo.addSafetyAlert(SafetyAlert(today, timeText, "Medicine not confirmed",
                            "The ${entry.timeLabel.lowercase()} $name dose was not confirmed within an hour of its reminder. Recorded for the caregiver — no message was sent anywhere by this build."))
                    }
                }
            }
        }
    }
}
