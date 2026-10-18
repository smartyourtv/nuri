package com.app.nuri.unstuck.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.app.nuri.R

/**
 * Emergency info on the lock screen (sweep #20): a persistent,
 * lock-screen-visible notification carrying the family-entered emergency
 * information (name, who to call, and what helpers should know — exactly
 * as the family wrote it). OEM "Medical ID" screens vary; this is the
 * route that works on every Android phone. The senior's own words are
 * shown — Nuri adds nothing medical of its own.
 */
object EmergencyInfoCard {
    private const val CHANNEL_ID = "nuri_emergency_info"
    private const val NOTIFICATION_ID = 4242

    fun show(context: Context, infoText: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Emergency information", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Your emergency information, shown on the lock screen."
                    setShowBadge(false)
                },
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Emergency information")
            .setContentText(infoText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(infoText))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun hide(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }
}
