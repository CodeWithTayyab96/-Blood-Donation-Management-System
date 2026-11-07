package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Movement
import java.time.LocalDate

/**
 * Phase 3: stock forecasting from real dated movements.
 *
 * Pure and deterministic (dates are passed in, nothing reads the clock), so the
 * rules are unit tested with fixed dates. The app records a [Movement] on every
 * donation (inflow) and every fulfilment/wastage (outflow); forecasting the burn
 * from those is far more honest than guessing from `requests.units_needed`,
 * which includes unfulfilled and cancelled asks.
 */
object StockForecast {

    const val LOW_DAYS = 14
    const val CRITICAL_DAYS = 7

    enum class Status { SURPLUS, OK, LOW, CRITICAL }

    data class GroupForecast(
        val group: String,
        val stock: Int,
        /** Average units consumed per day over the look-back window. */
        val burnPerDay: Double,
        /** Days until stock runs out, or null when there is no recorded burn. */
        val daysRemaining: Int?,
        val status: Status
    ) {
        val statusCode: String get() = status.name.lowercase()
    }

    /**
     * Average daily outflow for [group] over the [windowDays] ending at [asOf].
     * Only negative (outflow) movements count; a group with no recorded
     * consumption returns 0.0, which the caller treats as "never runs out".
     */
    fun dailyBurnRate(
        movements: List<Movement>,
        group: String,
        asOf: LocalDate,
        windowDays: Long = 90
    ): Double {
        if (windowDays <= 0) return 0.0
        val from = asOf.minusDays(windowDays)
        val consumed = movements.filter { m ->
            m.bloodGroup == group &&
                m.delta < 0 &&
                runCatching { LocalDate.parse(m.date) }.getOrNull()
                    ?.let { d -> !d.isBefore(from) && !d.isAfter(asOf) } == true
        }.sumOf { -it.delta }
        return consumed.toDouble() / windowDays
    }

    /** Whole days of stock left, or null when nothing is being consumed. */
    fun daysOfStockRemaining(stock: Int, burnPerDay: Double): Int? =
        if (burnPerDay <= 0.0) null else (stock / burnPerDay).toInt()

    fun statusFor(daysRemaining: Int?): Status = when {
        daysRemaining == null -> Status.SURPLUS
        daysRemaining < CRITICAL_DAYS -> Status.CRITICAL
        daysRemaining < LOW_DAYS -> Status.LOW
        else -> Status.OK
    }

    /** One forecast per group present in [currentStock], sorted by group. */
    fun forecast(
        movements: List<Movement>,
        currentStock: Map<String, Int>,
        asOf: LocalDate,
        windowDays: Long = 90
    ): List<GroupForecast> = currentStock.map { (group, stock) ->
        val burn = dailyBurnRate(movements, group, asOf, windowDays)
        val days = daysOfStockRemaining(stock, burn)
        GroupForecast(group, stock, burn, days, statusFor(days))
    }.sortedBy { it.group }
}
