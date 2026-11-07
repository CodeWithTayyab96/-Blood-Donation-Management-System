package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class LowStockTest {

    private val stock = linkedMapOf(
        "O-" to 0, "O+" to 2, "A-" to 3, "A+" to 1, "B+" to 9
    )

    @Test
    fun `the threshold is inclusive and negative stock counts as low`() {
        val low = LowStock.groups(stock + ("B-" to -1))
        assertTrue(low.contains("O-" to 0))
        assertTrue(low.contains("O+" to 2))
        assertTrue(low.contains("B-" to -1))
        assertFalse(low.contains("A-" to 3))
    }

    @Test
    fun `lowest units come first with a stable name tie break`() {
        assertEquals(
            listOf("O-" to 0, "A+" to 1, "O+" to 2),
            LowStock.groups(stock)
        )
    }

    @Test
    fun `a custom threshold is honoured`() {
        assertEquals(listOf("O-" to 0), LowStock.groups(stock, 0))
    }

    @Test
    fun `empty stock yields no low groups`() {
        assertTrue(LowStock.groups(emptyMap()).isEmpty())
    }
}
