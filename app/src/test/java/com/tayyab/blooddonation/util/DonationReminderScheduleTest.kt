package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DonationReminderScheduleTest {

    private val today = LocalDate.of(2026, 11, 7)

    @Test
    fun `the reminder date is the last donation plus the 90 day eligibility gap`() {
        val last = LocalDate.of(2026, 10, 1)
        val expected = last.plusDays(BloodCompatibility.MIN_GAP_DAYS)
        assertEquals(expected, DonationReminderSchedule.nextEligibleDate(last.toString(), today))
        assertEquals(90L, BloodCompatibility.MIN_GAP_DAYS)
    }

    @Test
    fun `no reminder when there is no last donation`() {
        assertNull(DonationReminderSchedule.nextEligibleDate(null, today))
        assertNull(DonationReminderSchedule.nextEligibleDate("", today))
        assertNull(DonationReminderSchedule.nextEligibleDate("   ", today))
    }

    @Test
    fun `no reminder when the donor is already eligible`() {
        val old = today.minusDays(91).toString()
        assertNull(DonationReminderSchedule.nextEligibleDate(old, today))
        assertNull(DonationReminderSchedule.nextEligibleDate(today.minusDays(90).toString(), today))
    }

    @Test
    fun `one day before eligibility is still a future reminder`() {
        val last = today.minusDays(89)
        assertEquals(today.plusDays(1), DonationReminderSchedule.nextEligibleDate(last.toString(), today))
    }

    @Test
    fun `an unreadable date is ignored rather than crashing`() {
        assertNull(DonationReminderSchedule.nextEligibleDate("not-a-date", today))
        assertNull(DonationReminderSchedule.nextEligibleDate("2026-13-45", today))
    }

    @Test
    fun `a Supabase timestamptz is read by its date part`() {
        val last = LocalDate.of(2026, 10, 1)
        assertEquals(
            last.plusDays(90),
            DonationReminderSchedule.nextEligibleDate("2026-10-01T00:00:00+00:00", today)
        )
    }

    @Test
    fun `delay is measured to nine in the morning on the target date`() {
        val now = LocalDateTime.of(2026, 11, 6, 20, 0)
        val millis = DonationReminderSchedule.delayMillis(now, LocalDate.of(2026, 11, 7))
        assertEquals(13 * 60 * 60 * 1000L, millis)
    }

    @Test
    fun `a target moment already past yields zero instead of a negative delay`() {
        val now = LocalDateTime.of(2026, 11, 7, 10, 0)
        assertEquals(0L, DonationReminderSchedule.delayMillis(now, LocalDate.of(2026, 11, 7)))
        assertEquals(0L, DonationReminderSchedule.delayMillis(now, LocalDate.of(2026, 11, 6)))
    }
}
