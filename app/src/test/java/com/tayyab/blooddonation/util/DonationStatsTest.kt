package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DonationStatsTest {

    @Test
    fun `no donations yields empty stats and first milestone pending`() {
        val stats = DonationStats.fromDates(emptyList())
        assertEquals(0, stats.totalDonations)
        assertEquals(0, stats.streak)
        assertEquals(0, stats.mayHaveHelped)
        assertTrue(stats.earnedMilestones.isEmpty())
        assertEquals(1, stats.nextMilestone)
    }

    @Test
    fun `single donation is a streak of one`() {
        val stats = DonationStats.fromDates(listOf(LocalDate.of(2026, 1, 1)))
        assertEquals(1, stats.totalDonations)
        assertEquals(1, stats.streak)
        assertEquals(3, stats.mayHaveHelped)
        assertEquals(listOf(1), stats.earnedMilestones)
        assertEquals(3, stats.nextMilestone)
    }

    @Test
    fun `milestones at 1 3 5 10 are awarded at the right counts`() {
        assertEquals(listOf(1, 3, 5), DonationStats.fromDates(listOfN(5)).earnedMilestones)
        val ten = DonationStats.fromDates(listOfN(10))
        assertEquals(listOf(1, 3, 5, 10), ten.earnedMilestones)
        assertNull(ten.nextMilestone)
    }

    @Test
    fun `helped counter is three per donation`() {
        assertEquals(30, DonationStats.fromDates(listOfN(10)).mayHaveHelped)
        assertEquals(15, DonationStats.fromDates(listOfN(5)).mayHaveHelped)
    }

    @Test
    fun `streak breaks after a long gap`() {
        // 273 days between the two donations, well over the 180-day cap.
        val dates = listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1))
        assertEquals(1, DonationStats.fromDates(dates).streak)
    }

    @Test
    fun `streak holds across a short gap`() {
        val dates = listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))
        assertEquals(2, DonationStats.fromDates(dates).streak)
    }

    @Test
    fun `from string list parses ISO dates and ignores bad ones`() {
        val stats = DonationStats.from(listOf("2026-01-01", "not-a-date", "2026-04-01"))
        assertEquals(2, stats.totalDonations)
        assertEquals(2, stats.streak)
    }

    private fun listOfN(n: Int) = List(n) { LocalDate.of(2026, 1, 1).plusDays(it.toLong() * 30) }
}
