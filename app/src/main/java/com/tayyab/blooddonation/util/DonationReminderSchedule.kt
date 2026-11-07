package com.tayyab.blooddonation.util

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure date maths for the "you can donate again" reminder.
 *
 * The interval is NOT duplicated here: it is [BloodCompatibility.MIN_GAP_DAYS],
 * the same 90-day rule the eligibility checker and the profile screen use. A
 * reminder that disagreed with "Next eligible date" would be worse than no
 * reminder at all.
 *
 * WorkManager wants a delay in milliseconds, not a date; keeping that
 * conversion here (with an injectable "now") makes it testable without a clock,
 * a device or a time zone.
 */
object DonationReminderSchedule {

    /** A 09:00 reminder is daytime, but early enough to plan a donation. */
    const val REMINDER_HOUR = 9

    /**
     * The date the donor becomes eligible again, or null when there is nothing
     * to remind them about: no last donation, an unreadable date, or the 90-day
     * wait is already over (they can donate today).
     *
     * Supabase sends a full timestamptz for last_donation_date while local rows
     * hold a plain `yyyy-MM-dd`; taking the first ten characters handles both.
     */
    fun nextEligibleDate(
        lastDonation: String?,
        today: LocalDate,
        minGapDays: Long = BloodCompatibility.MIN_GAP_DAYS
    ): LocalDate? {
        if (lastDonation.isNullOrBlank()) return null
        val last = runCatching {
            LocalDate.parse(lastDonation.trim().take(10))
        }.getOrNull() ?: return null
        val next = last.plusDays(minGapDays)
        return if (next.isAfter(today)) next else null
    }

    /**
     * Milliseconds from [now] until 09:00 on [targetDate]. Returns 0 when that
     * moment has passed (a reinstall, or a phone that was off for days), so the
     * reminder fires promptly instead of being silently lost.
     */
    fun delayMillis(
        now: LocalDateTime,
        targetDate: LocalDate,
        hourOfDay: Int = REMINDER_HOUR
    ): Long {
        val target = targetDate.atTime(hourOfDay.coerceIn(0, 23), 0)
        if (!target.isAfter(now)) return 0L
        return Duration.between(now, target).toMillis()
    }
}
