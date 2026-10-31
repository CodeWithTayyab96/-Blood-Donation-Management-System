package com.tayyab.blooddonation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.ui.RequestDetailActivity

/**
 * Posts one notification per matching blood request, and remembers which
 * requests have already been announced so the same one never alerts twice.
 *
 * "Never twice" is the part that needs storage: [RequestAlertWorker] runs every
 * ~15 minutes and sees the same still-pending requests on every pass, so the
 * notified ids live in their own SharedPreferences file rather than in memory.
 * That file holds nothing sensitive — just request ids.
 */
object RequestAlerts {

    private const val CHANNEL_ID = "urgent_requests"
    private const val PREFS = "request_alerts"
    private const val KEY_NOTIFIED = "notified_request_ids"

    /** Safety net so the remembered-id set cannot grow without bound. */
    private const val MAX_REMEMBERED = 500

    /**
     * Whether [request] is worth alerting [donor] about: it is still pending,
     * and the donor's group is compatible with the patient's.
     *
     * The donor's own eligibility (age, weight, 90-day gap) is checked once per
     * run by the worker, not per request, because it does not vary by request.
     */
    fun matches(donor: Donor, request: BloodRequest): Boolean =
        request.status == "Pending" &&
            BloodCompatibility.isCompatible(donor.bloodGroup, request.bloodGroup)

    /** Creates the channel once; a no-op afterwards. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Urgent blood requests",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Blood requests that match your blood group"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * One notification id per request, so two different requests never
     * overwrite each other's notification and the same request always reuses
     * its own.
     */
    fun notificationId(requestId: Long): Int = (requestId and 0x7FFFFFFF).toInt()

    /** Shows the alert. Safe to call without the notification permission. */
    fun notify(context: Context, request: BloodRequest) {
        ensureChannel(context)

        val open = Intent(context, RequestDetailActivity::class.java).apply {
            putExtra("requestId", request.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(request.id),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The patient's name is deliberately left out: a donor does not need it
        // to decide, and it would otherwise appear on a lock screen.
        val summary =
            "${request.unitsNeeded} unit(s) of ${request.bloodGroup} needed at ${request.hospital}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_drop)
            .setContentTitle("${request.urgency} blood request")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\nContact: ${request.contact}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId(request.id), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+. The request is
            // still marked as notified below, so it will not retry forever.
        }
    }

    fun notifiedIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_NOTIFIED, emptySet())
            ?: emptySet()

    fun markNotified(context: Context, requestId: Long) {
        val ids = notifiedIds(context).toMutableSet()
        ids.add(requestId.toString())
        val kept = if (ids.size > MAX_REMEMBERED) {
            ids.toList().takeLast(MAX_REMEMBERED).toSet()
        } else {
            ids
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_NOTIFIED, kept).apply()
    }

    /**
     * Drops remembered ids that no longer exist at all. Ids of fulfilled
     * requests are kept on purpose — that is what stops them alerting again if
     * the row is ever re-read.
     */
    fun prune(context: Context, existing: Set<String>) {
        val current = notifiedIds(context)
        val retained = current.intersect(existing)
        if (retained.size == current.size) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_NOTIFIED, retained).apply()
    }
}
