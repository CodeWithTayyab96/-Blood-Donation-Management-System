package com.tayyab.blooddonation.util

import java.time.LocalDate

/**
 * Milestones, streaks and the "people you may have helped" estimate, computed
 * from the donation history the app already has.
 *
 * Pure Kotlin so the rules can be unit tested without a device.
 *
 * Two deliberate limits:
 * - The helped counter is an *estimate*, labelled as such in the UI. One
 *   donation can be split into red cells, plasma and platelets, so "up to three
 *   patients" is the standard figure — it is not a promise that three people
 *   were helped.
 * - A "streak" here means consecutive donations with no long gap, not
 *   consecutive days. Nobody can donate daily, so a day-based streak would be
 *   meaningless.
 */
object DonationStats {

    /** The badges the app awards, in order. */
    val MILESTONES: List<Int> = listOf(1, 3, 5, 10)

    /**
     * Phase 2: the visible badges. FIRST/THREE/FIVE/TEN mirror [MILESTONES];
     * LIFESAVER is the simple "level" — five or more donations with a current
     * streak of at least three, i.e. someone who keeps coming back.
     */
    enum class Badge { FIRST, THREE, FIVE, TEN, LIFESAVER }

    /** Pure, so the badge rules are unit tested without an Android runtime. */
    fun badgesFor(totalDonations: Int, streak: Int): List<Badge> = buildList {
        if (totalDonations >= 1) add(Badge.FIRST)
        if (totalDonations >= 3) add(Badge.THREE)
        if (totalDonations >= 5) add(Badge.FIVE)
        if (totalDonations >= 10) add(Badge.TEN)
        if (totalDonations >= 5 && streak >= 3) add(Badge.LIFESAVER)
    }

    /**
     * A gap longer than this ends a streak. Twice the 90-day minimum, so a donor
     * who is a few weeks late does not lose the run.
     */
    private const val STREAK_GAP_DAYS = 180L

    /** Red cells, plasma and platelets — the usual "up to three patients" figure. */
    private const val PATIENTS_PER_DONATION = 3

    data class Stats(
        val totalDonations: Int,
        val streak: Int,
        /** Upper bound, not a count. Labelled "up to" wherever it is shown. */
        val mayHaveHelped: Int,
        val earnedMilestones: List<Int>,
        val nextMilestone: Int?,
        /** Phase 2: badges shown on the profile / home screen. */
        val earnedBadges: List<Badge> = emptyList()
    )

    /** Parses ISO dates, ignoring anything unreadable rather than throwing. */
    fun from(donationDates: List<String>): Stats {
        val parsed = donationDates.mapNotNull { raw ->
            runCatching { LocalDate.parse(raw.trim().take(10)) }.getOrNull()
        }
        return fromDates(parsed)
    }

    fun fromDates(dates: List<LocalDate>): Stats {
        val sorted = dates.sorted()
        val total = sorted.size
        val streak = streakOf(sorted)
        return Stats(
            totalDonations = total,
            streak = streak,
            mayHaveHelped = total * PATIENTS_PER_DONATION,
            earnedMilestones = MILESTONES.filter { total >= it },
            nextMilestone = MILESTONES.firstOrNull { total < it },
            earnedBadges = badgesFor(total, streak)
        )
    }

    /**
     * Walks back from the most recent donation, counting consecutive donations
     * that are within [STREAK_GAP_DAYS] of the one before. One donation is a
     * streak of one; no donations is zero.
     */
    private fun streakOf(sortedDates: List<LocalDate>): Int {
        if (sortedDates.isEmpty()) return 0
        var streak = 1
        for (i in sortedDates.lastIndex downTo 1) {
            val gap = sortedDates[i].toEpochDay() - sortedDates[i - 1].toEpochDay()
            if (gap > STREAK_GAP_DAYS) break
            streak++
        }
        return streak
    }
}
