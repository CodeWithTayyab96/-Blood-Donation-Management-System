package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Movement
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StockForecastTest {

    private val asOf = LocalDate.of(2026, 11, 7)

    @Test
    fun `burn rate counts only outflow within the window`() {
        val m = listOf(
            Movement(1, "O-", -5, "fulfilment", "2026-11-01"),
            Movement(2, "O-", -10, "fulfilment", "2026-10-15"),
            Movement(3, "O-", 20, "donation", "2026-11-02"),     // inflow ignored
            Movement(4, "O-", -3, "wastage", "2020-01-01")        // outside window
        )
        // 15 units consumed across 90 days
        assertEquals(15.0 / 90.0, StockForecast.dailyBurnRate(m, "O-", asOf), 1e-9)
    }

    @Test
    fun `no outflow means never runs out`() {
        val m = listOf(Movement(1, "A+", 10, "donation", "2026-11-01"))
        assertEquals(0.0, StockForecast.dailyBurnRate(m, "A+", asOf), 1e-9)
        assertNull(StockForecast.daysOfStockRemaining(50, 0.0))
    }

    @Test
    fun `days remaining is stock divided by burn`() {
        // 30 units, burn 15/90 per day -> 30 * 90 / 15 = 180 days
        assertEquals(180, StockForecast.daysOfStockRemaining(30, 15.0 / 90.0))
    }

    @Test
    fun `status thresholds`() {
        assertSame(StockForecast.Status.SURPLUS, StockForecast.statusFor(10, null))
        assertSame(StockForecast.Status.CRITICAL, StockForecast.statusFor(0, null))
        assertSame(StockForecast.Status.CRITICAL, StockForecast.statusFor(5, 3))
        assertSame(StockForecast.Status.LOW, StockForecast.statusFor(10, 10))
        assertSame(StockForecast.Status.OK, StockForecast.statusFor(50, 20))
    }

    @Test
    fun `forecast returns one entry per group sorted by group`() {
        val m = listOf(Movement(1, "O-", -15, "fulfilment", "2026-11-01"))
        val stock = mapOf("O-" to 30, "A+" to 0)
        val f = StockForecast.forecast(m, stock, asOf)
        assertEquals(listOf("A+", "O-"), f.map { it.group })
        assertSame(StockForecast.Status.CRITICAL, f[0].status)   // A+ stock=0
        assertSame(StockForecast.Status.OK, f[1].status)        // O- ~180 days
    }
}
