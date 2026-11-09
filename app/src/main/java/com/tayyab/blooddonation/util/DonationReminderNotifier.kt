package com.tayyab.blooddonation.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tayyab.blooddonation.R

/**
 * The one-off "you can donate again" notification.
 *
 * Its OWN channel, for the same reason tips have one: a donor who mutes daily
 * tips must still be able to receive (or mute) the eligibility reminder
 * independently of urgent-request alerts. All wording comes from strings.xml
 * so it is translated with the rest of the app.
 */
object DonationReminderNotifier {

    private const val TAG = "DonationReminder"
    private const val CHANNEL_ID = "donation_reminders"
    private const val NOTIFICATION_ID = 4302

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    // The permission check just below is what lint cannot see through, so the
    // explicit check (and the SecurityException catch) is the real guard.
    @SuppressLint("MissingPermission")
    fun post(context: Context) {
        // Android 13+ silently drops notifications without this permission.
        // Both home screens ask for it once; here we simply check it and log the
        // reason instead of pretending the reminder was delivered.
        // POST_NOTIFICATIONS only exists on API 33+; asking checkSelfPermission
        // for it on an older phone always reports "denied" and would suppress
        // every reminder on Android 8–12.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; eligibility reminder not posted")
            return
        }
        ensureChannel(context)
        val body = context.getString(R.string.reminder_notification_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_drop)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Still log it: a missing permission must never vanish silently.
            Log.w(TAG, "Notification permission revoked while posting", e)
        }
    }
}
