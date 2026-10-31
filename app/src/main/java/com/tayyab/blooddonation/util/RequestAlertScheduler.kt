package com.tayyab.blooddonation.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the single periodic job that polls for urgent requests.
 *
 * 15 minutes is WorkManager's minimum period for periodic work, so "about
 * every 15 minutes" is the fastest this design can go — and in practice it is
 * slower, because the system batches jobs and Doze can defer them for hours.
 * That is the accepted trade-off for not using a push service: no Firebase, no
 * server, no Google account, at the cost of approximate delivery.
 */
object RequestAlertScheduler {

    private const val WORK_NAME = "request-alerts"
    private const val PERIOD_MINUTES = 15L

    /**
     * Starts polling for a donor. [ExistingPeriodicWorkPolicy.KEEP] leaves an
     * already-scheduled job alone, so opening the app repeatedly does not keep
     * resetting the interval and starving the poll.
     */
    fun schedule(context: Context) {
        if (!SupabaseSync.isOnline) return
        val request = PeriodicWorkRequestBuilder<RequestAlertWorker>(
            PERIOD_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Stops polling — used on sign-out and for accounts that are not donors. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
