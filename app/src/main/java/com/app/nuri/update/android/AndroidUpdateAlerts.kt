package com.app.nuri.update.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.nuri.update.UpdateAlertGateway

/**
 * Caregiver alert + after-note delivery (brief SS10). Every alert is
 * RECORDED in [UpdateRuntimeStore] first (so it survives in Settings even
 * when notifications are off), then posted as a local notification when the
 * runtime permission allows. On the senior's own phone this is a quiet local
 * notice; the caregiver-facing record is what the dashboard layer will read
 * when real dashboard data replaces the sample repository.
 */
class AndroidUpdateAlerts(context: Context, private val store: UpdateRuntimeStore) : UpdateAlertGateway {

    private val app = context.applicationContext

    override fun caregiverAlert(title: String, detail: String) {
        store.recordCaregiverAlert(title, detail)
        store.recordRunResult(detail)
        post(title, detail)
    }

    override fun afterNote(text: String) {
        store.recordAfterNote(text)
        post("Nuri", text)
    }

    private fun post(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // recorded above; never nag for a permission from a background path
        }
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nuri updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Quiet notes about Nuri updates — nothing ever looks different."
            }
        )
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                app, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Recorded in the store; posting is best-effort.
        }
    }

    private companion object {
        const val CHANNEL_ID = "nuri_updates"
        const val NOTIFICATION_ID = 4201
    }
}
