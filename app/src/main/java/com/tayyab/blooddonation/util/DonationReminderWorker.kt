package com.tayyab.blooddonation.util

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tayyab.blooddonation.db.DbHelper
import java.time.LocalDate

/**
 * Fires once, on the day a donor becomes eligible again (last donation + the
 * same 90-day gap the rest of the app uses).
 *
 * Everything is read from local SQLite on WorkManager's background thread: no
 * network call, no main-thread work, and no need for an internet connection on
 * the day itself. If a newer donation was recorded after this work was queued,
 * the worker reschedules itself instead of announcing the wrong date.
 */
class DonationReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "DonationReminderWorker"
    }

    override fun doWork(): Result {
        return try {
            val context = applicationContext
            Session.load(context)
            val donorId = Session.donorId
            val donor = donorId?.let { DbHelper(context).getDonor(it) }

            when {
                // Signed out, account deleted, or the local history was wiped:
                // there is nobody to remind.
                donor == null || donor.lastDonationDate.isNullOrBlank() -> {
                    DonationReminderScheduler.cancel(context)
                }
                else -> {
                    val today = LocalDate.now()
                    val next = DonationReminderSchedule.nextEligibleDate(donor.lastDonationDate, today)
                    if (next != null && next.isAfter(today)) {
                        // A donation arrived after this one-time job was queued.
                        DonationReminderScheduler.schedule(context, next)
                    } else {
                        DonationReminderNotifier.post(context)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            // Never swallow a background failure silently: WorkManager's
            // backoff will retry, and the log says why it did.
            Log.e(TAG, "Eligibility reminder failed", e)
            Result.failure()
        }
    }
}
