package com.tayyab.blooddonation.util

import android.content.Context
import com.tayyab.blooddonation.R

/**
 * Turns the pure [DonationStats.Badge] list into translated display text.
 * The rules stay in DonationStats (unit tested); this object only maps a badge
 * to a strings.xml label, so English and Urdu both work.
 */
object BadgeUi {

    fun text(context: Context, badges: List<DonationStats.Badge>): String =
        if (badges.isEmpty()) {
            context.getString(R.string.badges_none)
        } else {
            context.getString(R.string.badges_earned) + " " +
                badges.joinToString(" • ") { context.getString(label(it)) }
        }

    private fun label(badge: DonationStats.Badge): Int = when (badge) {
        DonationStats.Badge.FIRST -> R.string.badge_first
        DonationStats.Badge.THREE -> R.string.badge_three
        DonationStats.Badge.FIVE -> R.string.badge_five
        DonationStats.Badge.TEN -> R.string.badge_ten
        DonationStats.Badge.LIFESAVER -> R.string.badge_lifesaver
    }
}
