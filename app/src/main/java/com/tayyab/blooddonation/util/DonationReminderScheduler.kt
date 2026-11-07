package com.tayyab.blooddonation.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tayyab.blooddonation.db.DbHelper
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules / reschedules / cancels the one-time eligibility reminder.
 *
 * REPLACE (not KEEP) is deliberate: every recorded donation moves the eligible
 * date, so the pending job must be replaced rather than left to fire on a date
 * that is now wrong. Cancelling on logout and account deletion stops a signed
 * out phone from being woken for somebody else's reminder.
 */
object DonationReminderScheduler {

    private const val WORK_NAME = "donation-eligibility-reminder"

    /** Queues one notification for 09:00 on [eligibleDate]. */
    fun schedule(context: Context, eligibleDate: LocalDate) {
        val delay = DonationReminderSchedule.delayMillis(LocalDateTime.now(), eligibleDate)
        val request = OneTimeWorkRequestBuilder<DonationReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            // Local-only work: no network constraint, because the reminder must
            // still arrive on a phone that is offline on the day. No charging
            // or battery constraint either — a date-specific reminder that can
            // be dropped is no reminder at all. WorkManager's own 10-minute
            // worker limit is the timeout, and the work itself is a single
            // local read plus one notification.
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Recomputes the date from this donor's local row. Called when a donation
     * is recorded and whenever a donor reaches a home screen, so a phone that
     * was off for a week still has the right job queued.
     */
    fun rescheduleForDonor(context: Context, donorId: Long?) {
        if (donorId == null) {
            cancel(context)
            return
        }
        val donor = DbHelper(context.applicationContext).getDonor(donorId)
        val next = DonationReminderSchedule.nextEligibleDate(
            donor?.lastDonationDate,
            LocalDate.now()
        )
        if (next == null) cancel(context) else schedule(context, next)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}
