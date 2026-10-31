package com.tayyab.blooddonation.util

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

    companion object {
        /**
         * How many alerts this run posted. Read back from the WorkInfo output
         * by the debug-only "check requests now" button; the periodic job
         * ignores it.
         */
        const val KEY_ALERT_COUNT = "alert_count"
    }

    override fun doWork(): Result {
        val context = applicationContext
        Session.load(context)

        // Only a donor with a profile receives alerts, and only when a Supabase
        // project is configured — without it there is nothing to poll.
        val donorId = Session.donorId
        if (Session.role != Session.ROLE_DONOR || donorId == null) {
            return Result.success(workDataOf(KEY_ALERT_COUNT to 0))
        }
        if (!SupabaseSync.isOnline) {
            return Result.success(workDataOf(KEY_ALERT_COUNT to 0))
        }

        // null means the pull failed (network, permissions, server), which is
        // worth a retry. A genuine zero is a success with nothing to report.
        val count = try {
            alertNewMatches(context, donorId)
        } catch (e: Exception) {
            // A failure here must not crash the process; let WorkManager retry
            // with its backoff instead.
            null
        } ?: return Result.retry()

        return Result.success(workDataOf(KEY_ALERT_COUNT to count))
    }

    /** Returns how many alerts were posted, or null when the pull failed. */
    private fun alertNewMatches(context: Context, donorId: Long): Int? {
        val db = DbHelper(context)

        // Pull first. A request raised on another phone only exists locally once
        // it has been downloaded, so reading the local copy without this would
        // never see it.
        if (!SupabaseSync.pullRequests(db)) return null

        val donor = db.getDonor(donorId) ?: return 0
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
}
