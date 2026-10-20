package com.app.nuri.call.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.nuri.MainActivity

/**
 * Raises the call surface when a call arrives while Ilona is in
 * the background (design §17.3): a full-screen-intent notification
 * that brings MainActivity forward, where the overlay (Talk / No /
 * End) lives. If the intent can't fire, the announcement still
 * speaks from the background and the caregiver sees an honest
 * no-answer — never a silent call.
 */
object CallAlerts {

    private const val CHANNEL_ID = "ilona_calls"
    private const val NOTIFICATION_ID = 4401

    fun raise(context: Context, title: String, text: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A call from your caregiver is starting."
            },
        )
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreen = PendingIntent.getActivity(
            app, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Announcement still speaks; the surface raises when Ilona is next opened.
        }
    }

    fun clear(context: Context) {
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
    }
}
