package com.tayyab.blooddonation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.ui.MainActivity

/**
 * Notifications for the in-app updater.
 *
 * A separate channel from request alerts and tips: someone who mutes update
 * notices must still receive blood-request alerts. IMPORTANCE_LOW, because an
 * update is never urgent, and a missing POST_NOTIFICATIONS permission is
 * swallowed rather than crashing.
 */
object UpdateNotifier {

    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_AVAILABLE = 4300

    /** Read by MainActivity: the notification was tapped, so show the prompt. */
    const val EXTRA_SHOW_UPDATE_PROMPT = "show_update_prompt"
    private const val NOTIFICATION_RESULT = 4301

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tells you when a newer version of the app is available."
        }
        manager.createNotificationChannel(channel)
    }

    /** "Version 1.8 is available" — tapping opens the app's update prompt. */
    fun updateAvailable(context: Context, info: AppUpdater.UpdateInfo) {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_SHOW_UPDATE_PROMPT, true)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(context, 0, open, flags)
        val version = info.versionName.ifBlank { "a newer version" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_drop)
            .setContentTitle("Version $version is available")
            .setContentText("Tap to download and install it.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        notify(context, NOTIFICATION_AVAILABLE, notification)
    }

    /** The install finished; the app is about to restart into the new version. */
    fun installed(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_drop)
            .setContentTitle("Update installed")
            .setContentText("The new version is ready. Open the app to use it.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        notify(context, NOTIFICATION_RESULT, notification)
    }

    /** The install was refused, with the system's own reason. */
    fun failed(context: Context, message: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_drop)
            .setContentTitle("Update could not be installed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notify(context, NOTIFICATION_RESULT, notification)
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+. The in-app prompt
            // still works; nothing to do here.
        }
    }
}
