package com.tayyab.blooddonation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.ui.RequestDetailActivity

/**
 * Peer-to-peer offer notifications (Phase 3): "someone offered on your
 * request" for the requester, "your offer was accepted" for the donor.
 *
 * Deliberately a calmer companion to [RequestAlerts] (which owns the HIGH
 * emergency channel): no offer is an emergency, so this channel is DEFAULT
 * importance, and the two channels are named separately so a user can turn
 * peer activity off in system settings without silencing urgent requests.
 *
 * Like the urgent alerts, each distinct push is shown at most once per
 * offer event, in its own prefs file — meaning both delivery paths (push
 * and the 15-minute poll fallback) share the same dedup and never show
 * one event twice.
 */
object PeerAlerts {

    private const val CHANNEL_ID = "peer_activity"
    private const val PREFS = "peer_alerts"
    private const val KEY_SHOWN = "shown"

    /** Safety net so the shown-set cannot grow without bound. */
    private const val MAX_REMEMBERED = 500

    /** One dedup key per offer event kind; stable across delivery paths. */
    fun dedupKey(kind: String, offerId: Long): String = "$kind:$offerId"

    fun shown(context: Context, kind: String, offerId: Long): Boolean =
        dedupKey(kind, offerId) in (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SHOWN, emptySet()) ?: emptySet())

    fun markShown(context: Context, kind: String, offerId: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = (prefs.getStringSet(KEY_SHOWN, emptySet()) ?: emptySet()).toMutableSet()
        if (!ids.add(dedupKey(kind, offerId))) return  // already there, untouched
        val kept = if (ids.size > MAX_REMEMBERED) {
            ids.toList().takeLast(MAX_REMEMBERED).toSet()
        } else {
            ids
        }
        prefs.edit().putStringSet(KEY_SHOWN, kept).apply()
    }

    /** Creates the channel once; a no-op afterwards. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Offers and acceptances",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "People offering to donate, and offers accepted"
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * One notification id per (request, offer): two offers on the same request
     * are two stackable notifications, and re-showing the same event replaces
     * its own notification rather than piling up.
     */
    fun notificationId(requestId: Long, offerId: Long): Int =
        (requestId xor (offerId shl 1)).and(0x7FFFFFFFL).toInt()

    /** Tapping either kind of peer alert opens the request's detail screen. */
    private fun contentIntent(context: Context, requestId: Long, requestCode: Int): PendingIntent {
        val open = Intent(context, RequestDetailActivity::class.java).apply {
            putExtra("requestId", requestId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Shows the alert. Safe to call without the notification permission (the
     * post is swallowed, like in [RequestAlerts.notify]). Returns false when
     * this exact offer event had already been shown.
     */
    fun notify(context: Context, kind: String, requestId: Long, offerId: Long,
               title: String, body: String): Boolean {
        if (shown(context, kind, offerId)) return false
        ensureChannel(context)
        val id = notificationId(requestId, offerId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_drop)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // The full text contains only a first name — nothing needing the
            // public/private pair the urgent alerts use.
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, requestId, id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+; still mark shown
            // so a later poll cannot resurface the same event.
        }
        markShown(context, kind, offerId)
        return true
    }
}
