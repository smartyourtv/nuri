package com.app.nuri.remote.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.nuri.repository.SettingsRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM endpoint (design §10.1). On the Spark plan there is NO
 * server that can send pushes to this app (that needs Cloud
 * Functions/Blaze), so this service's honest jobs are exactly two:
 * register the token (used by the console "send test message"
 * proof in the troubleshooting panel) and show any message that
 * does arrive as a plain notification. Real alert delivery is the
 * watch service's job; nothing here pretends otherwise.
 */
class IlonaMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            runCatching { SettingsRepository(applicationContext).setRemoteFcmToken(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "Ilona"
        val body = message.notification?.body ?: message.data["body"] ?: "Test message received — watching is connected."
        post(title, body)
    }

    private fun post(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Test messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Only the connection test message uses this."
            },
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(4302, notification)
        } catch (_: SecurityException) {
            // Token is stored regardless; posting is best-effort.
        }
    }

    companion object {
        const val CHANNEL_ID = "ilona_test_messages"
    }
}
