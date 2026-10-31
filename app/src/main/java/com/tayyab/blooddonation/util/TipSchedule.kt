package com.tayyab.blooddonation.util

import android.content.Context

/**
 * The rules for the optional daily donation tip.
 *
 * Off by default, at most one per day, and never at night. The decision is a
 * pure function so it can be tested without a device; [setEnabled] and the
 * last-sent day are the only state.
 */
object TipSchedule {

    private const val PREFS = "donation_tips"
    private const val KEY_ENABLED = "tips_enabled"
    private const val KEY_LAST_DAY = "last_tip_epoch_day"

    /** 21:00 onwards counts as night. */
    const val QUIET_FROM_HOUR = 21

    /** 09:00 is the first hour a tip may arrive. */
    const val QUIET_UNTIL_HOUR = 9

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastSentEpochDay(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_DAY)) prefs.getLong(KEY_LAST_DAY, Long.MIN_VALUE) else null
    }

    fun markSent(context: Context, epochDay: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_DAY, epochDay).apply()
    }

    /**
     * Whether a tip may be posted right now.
     *
     * @param hourOfDay 0–23, the device's local hour.
     * @param lastSentEpochDay the day of the last tip, or null if there has
     *   never been one.
     */
    fun shouldSend(
        enabled: Boolean,
        hourOfDay: Int,
        lastSentEpochDay: Long?,
        todayEpochDay: Long
    ): Boolean {
        if (!enabled) return false
        if (hourOfDay < QUIET_UNTIL_HOUR || hourOfDay >= QUIET_FROM_HOUR) return false
        return lastSentEpochDay != todayEpochDay
    }
}
