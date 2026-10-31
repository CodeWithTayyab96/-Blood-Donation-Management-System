package com.tayyab.blooddonation.util

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Delivers the optional daily tip.
 *
 * Runs every six hours rather than once a day: a once-a-day job that happens to
 * fire at 03:00 would be skipped by the night rule and then not run again for
 * another 24 hours, so the donor would silently get nothing. Checking four times
 * a day means one of the runs lands in the allowed window, and the once-per-day
 * guard in [TipSchedule] still prevents a second tip.
 */
class TipWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val today = LocalDate.now().toEpochDay()
        val hour = LocalTime.now().hour
        val allowed = TipSchedule.shouldSend(
            enabled = TipSchedule.isEnabled(context),
            hourOfDay = hour,
            lastSentEpochDay = TipSchedule.lastSentEpochDay(context),
            todayEpochDay = today
        )
        if (!allowed) return Result.success()
        TipNotifier.post(context, MotivationFacts.tipFor(today))
        TipSchedule.markSent(context, today)
        return Result.success()
    }
}

/** Starts and stops the tip job. */
object TipScheduler {

    private const val WORK_NAME = "donation-tips"
    private const val PERIOD_HOURS = 6L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TipWorker>(PERIOD_HOURS, TimeUnit.HOURS).build()
        // KEEP: re-opening the app must not reset the schedule.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
