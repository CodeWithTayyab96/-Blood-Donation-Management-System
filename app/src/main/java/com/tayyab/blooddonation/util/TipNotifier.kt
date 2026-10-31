package com.tayyab.blooddonation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tayyab.blooddonation.R

/**
 * Posts the optional daily tip.
 *
 * A separate channel from the urgent-request alerts on purpose: tips are
 * low-importance and disposable, and putting them on the same channel would
 * mean a donor who mutes the tips also stops seeing blood-request alerts. The
 * permission handling is the same as everywhere else — nothing new is asked
 * for, and a missing POST_NOTIFICATIONS is swallowed rather than crashing.
 */
object TipNotifier {

    private const val CHANNEL_ID = "donation_tips"
    private const val NOTIFICATION_ID = 4200

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Donation tips",
            // LOW: no sound, no heads-up. A tip is not time critical.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "An occasional tip about donating blood. Off by default."
        }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, tip: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_drop)
            .setContentTitle("Donation tip")
            .setContentText(tip)
            .setStyle(NotificationCompat.BigTextStyle().bigText(tip))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+. Nothing to do.
        }
    }
}
