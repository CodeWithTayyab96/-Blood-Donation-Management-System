package com.tayyab.blooddonation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the distinction the fulfil flow got wrong: a stock count that could not
 * be read is not the same as a count of zero.
 */
class StockCheckTest {

    @Test
    fun `a count that could not be read is unknown, never zero`() {
        assertEquals(StockCheck.Decision.Unknown, StockCheck.decide(null, 1))
    }

    @Test
    fun `a real zero is a shortage and reports zero`() {
        val decision = StockCheck.decide(0, 1)
        assertTrue(decision is StockCheck.Decision.Short)
        assertEquals(0, (decision as StockCheck.Decision.Short).available)
    }

    @Test
    fun `exactly enough is sufficient`() {
        assertEquals(StockCheck.Decision.Sufficient, StockCheck.decide(1, 1))
    }

    @Test
    fun `more than enough is sufficient`() {
        assertEquals(StockCheck.Decision.Sufficient, StockCheck.decide(9, 2))
    }

    @Test
    fun `one short reports the count it saw`() {
        val decision = StockCheck.decide(1, 2)
        assertTrue(decision is StockCheck.Decision.Short)
        assertEquals(1, (decision as StockCheck.Decision.Short).available)
    }

    /**
     * The bug this exists for: with the old code a failed lookup and a genuine
     * shortage both ended in "not enough units in stock", so a user staring at
     * "In stock: 2" was told there was nothing.
     */
    @Test
    fun `unknown and short are different outcomes`() {
        assertNotEquals(StockCheck.decide(null, 5), StockCheck.decide(0, 5))
    }

    @Test
    fun `the stock check is not weakened`() {
        // Fulfilment must still be refused when the server really is short.
        for (available in 0..4) {
            assertEquals(
                "server has $available, needs 5",
                StockCheck.Decision.Short(available),
                StockCheck.decide(available, 5)
            )
        }
        assertEquals(StockCheck.Decision.Sufficient, StockCheck.decide(5, 5))
    }
}
