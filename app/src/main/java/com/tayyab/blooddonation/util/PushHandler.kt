package com.tayyab.blooddonation.util

import android.content.Context
import com.tayyab.blooddonation.db.DbHelper

/**
 * Routes one push payload into a notification and, for requests, also into
 * the local SQLite copy, so the list screens show the new request even when
 * the user never opens the alert. There are no permission decisions here:
 * the server already filtered recipients ("offer" goes to the requester,
 * "accepted" to the donor, "urgent_request" only to compatible, available,
 * eligible donors), and every branch is idempotent thanks to the shared dedup
 * in [RequestAlerts] / [PeerAlerts].
 *
 * Called from [BloodMessagingService] on a background thread, so blocking
 * database I/O is fine.
 */
object PushHandler {

    fun handle(context: Context, data: Map<String, String>) {
        val payload = try {
            PushParser.parse(data)
        } catch (_: Exception) {
            null
        } ?: return   // unknown, empty or malformed: nothing to do
        when (payload) {
            is PushData.UrgentRequest -> urgent(context, payload)
            is PushData.Offer -> PeerAlerts.notify(
                context, "offer", payload.requestId, payload.offerId, payload.title, payload.body
            )
            is PushData.OfferAccepted -> PeerAlerts.notify(
                context, "accepted", payload.requestId, payload.offerId, payload.title, payload.body
            )
        }
    }

    /** Stores the request locally, then shows the urgent alert if the poll
     *  has not shown this request already (and never re-shows it either). */
    private fun urgent(context: Context, payload: PushData.UrgentRequest) {
        val request = payload.request
        DbHelper(context).upsertRequest(request)
        if (request.id.toString() in RequestAlerts.notifiedIds(context)) return
        RequestAlerts.notify(context, request)
        RequestAlerts.markNotified(context, request.id)
    }
}
