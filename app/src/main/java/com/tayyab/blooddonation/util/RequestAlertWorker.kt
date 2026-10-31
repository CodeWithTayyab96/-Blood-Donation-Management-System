package com.tayyab.blooddonation.util

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.Donor

/**
 * Polls for blood activity the signed-in donor or requester has not been
 * told about: urgent requests their group can serve (the classic v1.6 alerts),
 * and — only when push is NOT configured, because then nothing tells them
 * otherwise — new offers on their requests and acceptances of their offers.
 *
 * Scheduled by [RequestAlertScheduler] roughly every 15 minutes, with a
 * network constraint so it never runs offline. Delivery is therefore
 * approximate: a request can sit unnoticed for up to one poll interval, and
 * Doze can delay it further. It is the FALLBACK: when Firebase is configured
 * (see [PushHandler]) the same dedup files make sure an event is never
 * announced twice, so both paths can run side by side.
 *
 * Runs on WorkManager's background thread, so blocking I/O is fine and the
 * existing synchronous clients can be used as-is.
 */
class RequestAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        /**
         * How many alerts this run posted. Read back from the WorkInfo output
         * by the debug-only "check requests now" button; the periodic job
         * ignores it.
         */
        const val KEY_ALERT_COUNT = "alert_count"

        /** Bound on how many requests get their offers refreshed per run. */
        private const val MAX_OFFER_PULLS = 10
    }

    override fun doWork(): Result {
        val context = applicationContext
        Session.load(context)

        // Alerts are for users with a donor profile (urgent alerts), plus any
        // signed-in user who posted a request (offer alerts). A Supabase
        // project must be configured either way — without it there is nothing
        // to poll.
        if (!SupabaseSync.isOnline) {
            return Result.success(workDataOf(KEY_ALERT_COUNT to 0))
        }

        val count = try {
            (Session.donorId?.let { alertUrgentRequests(context, it) } ?: 0) +
                alertOffers(context, DbHelper(context))
        } catch (e: Exception) {
            // A failure here must not crash the process; let WorkManager retry
            // with its backoff instead.
            null
        } ?: return Result.retry()

        return Result.success(workDataOf(KEY_ALERT_COUNT to count))
    }

    /** Returns how many urgent alerts were posted, or null when the pull failed. */
    private fun alertUrgentRequests(context: Context, donorId: Long): Int? {
        val db = DbHelper(context)

        // Pull first. A request raised on another phone only exists locally once
        // it has been downloaded, so reading the local copy without this would
        // never see it.
        if (!SupabaseSync.pullRequests(db)) return null

        val donor: Donor = db.getDonor(donorId) ?: return 0
        // One eligibility check for the whole run: it depends on the donor, not
        // on which request is being looked at.
        if (!BloodCompatibility.eligibility(donor).first) return 0

        val requests = db.getAllRequests()
        val alreadyNotified = RequestAlerts.notifiedIds(context)
        var count = 0
        for (request in requests) {
            if (request.id.toString() in alreadyNotified) continue
            if (!RequestAlerts.matches(donor, request)) continue
            RequestAlerts.notify(context, request)
            RequestAlerts.markNotified(context, request.id)
            count++
        }

        // Keep the remembered ids bounded, but only forget requests that are
        // gone entirely — a fulfilled request must stay remembered so it can
        // never alert a second time.
        RequestAlerts.prune(context, requests.map { it.id.toString() }.toSet())
        return count
    }

    /**
     * Offer alerts — the POLLING fallback only (when Firebase IS configured
     * the pushes carry these events, and [PushHandler] shows them; handing
     * this side over to the worker would just duplicate the work). Enabled
     * only when push is not configured.
     *
     * Two rules, sharing their dedup files with the push path via [PeerAlerts],
     * so one offer event is never announced by both paths:
     *  - a requester is alerted once about each new offer on one of their
     *    pending requests;
     *  - a donor is alerted once when one of their offers is accepted.
     *
     * An offer exists in SQLite from the moment its own device created it, so
     * the lists of requests to refresh are known locally; each is then pulled
     * fresh before reading. The per-run pull cap keeps old accounts bounded.
     */
    private fun alertOffers(context: Context, db: DbHelper): Int {
        if (FcmPush.isConfigured) return 0
        val me = Session.authUserId ?: return 0
        var count = 0

        val myRequests = db.getAllRequests()
            .filter { it.requesterId == me && it.status == "Pending" }
        for (request in myRequests.take(MAX_OFFER_PULLS)) {
            SupabaseSync.pullOffersForRequest(db, request.id)
            for (offer in db.getOffersForRequest(request.id)) {
                if (offer.status != "Offered") continue
                val who = offer.donorName.substringBefore(' ').ifBlank { "Someone" }
                if (PeerAlerts.notify(
                        context, "offer", offer.requestId, offer.id,
                        "New offer on your request",
                        "$who (${offer.donorGroup.ifBlank { "?" }}) offered to donate"
                    )
                ) count++
            }
        }

        val myOfferRequestIds = db.getAllOffers()
            .filter { it.donorAuthId == me }
            .map { it.requestId }
            .distinct()
        for (requestId in myOfferRequestIds.take(MAX_OFFER_PULLS)) {
            SupabaseSync.pullOffersForRequest(db, requestId)
            for (offer in db.getOffersForRequest(requestId)) {
                if (offer.donorAuthId != me || offer.status != "Accepted") continue
                if (PeerAlerts.notify(
                        context, "accepted", offer.requestId, offer.id,
                        "Your offer was accepted",
                        "Contact details are in the app. Donate at a licensed blood bank or hospital."
                    )
                ) count++
            }
        }
        return count
    }
}
