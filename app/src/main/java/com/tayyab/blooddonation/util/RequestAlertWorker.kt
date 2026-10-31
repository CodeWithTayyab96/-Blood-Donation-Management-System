package com.tayyab.blooddonation.util

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tayyab.blooddonation.db.DbHelper

/**
 * Polls for pending blood requests and alerts the signed-in donor about the
 * ones they can actually help with.
 *
 * Scheduled by [RequestAlertScheduler] roughly every 15 minutes, with a network
 * constraint so it never runs offline. Delivery is therefore approximate: a
 * request can sit unnoticed for up to one poll interval, and Doze can delay it
 * further. Nothing here is real-time.
 *
 * Runs on WorkManager's background thread, so blocking I/O is fine and the
 * existing synchronous clients can be used as-is.
 */
class RequestAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        Session.load(context)

        // Only a donor with a profile receives alerts, and only when a Supabase
        // project is configured — without it there is nothing to poll.
        val donorId = Session.donorId
        if (Session.role != Session.ROLE_DONOR || donorId == null) return Result.success()
        if (!SupabaseSync.isOnline) return Result.success()

        return try {
            alertNewMatches(context, donorId)
            Result.success()
        } catch (e: Exception) {
            // A failure here must not crash the process; let WorkManager retry
            // with its backoff instead.
            Result.retry()
        }
    }

    private fun alertNewMatches(context: Context, donorId: Long): Result {
        val db = DbHelper(context)

        // Pull first. A request raised on another phone only exists locally once
        // it has been downloaded, so reading the local copy without this would
        // never see it.
        if (!SupabaseSync.pullRequests(db)) return Result.retry()

        val donor = db.getDonor(donorId) ?: return Result.success()
        // One eligibility check for the whole run: it depends on the donor, not
        // on which request is being looked at.
        if (!BloodCompatibility.eligibility(donor).first) return Result.success()

        val requests = db.getAllRequests()
        val alreadyNotified = RequestAlerts.notifiedIds(context)
        for (request in requests) {
            if (request.id.toString() in alreadyNotified) continue
            if (!RequestAlerts.matches(donor, request)) continue
            RequestAlerts.notify(context, request)
            RequestAlerts.markNotified(context, request.id)
        }

        // Keep the remembered ids bounded, but only forget requests that are
        // gone entirely — a fulfilled request must stay remembered so it can
        // never alert a second time.
        RequestAlerts.prune(context, requests.map { it.id.toString() }.toSet())
        return Result.success()
    }
}
