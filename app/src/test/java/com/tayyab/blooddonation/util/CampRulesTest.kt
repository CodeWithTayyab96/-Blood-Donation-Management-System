package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Camp
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CampRulesTest {

    private fun camp(date: String) = Camp(id = 0, name = "C", location = "L", city = "Lahore", campDate = date)

    @Test
    fun `upcoming and past split on asOf`() {
        val asOf = LocalDate.of(2026, 11, 7)
        assertTrue(CampRules.isUpcoming(camp("2026-11-20"), asOf))
        assertTrue(CampRules.isUpcoming(camp("2026-11-07"), asOf))  // same day counts as upcoming, so it shows in the list
        assertFalse(CampRules.isPast(camp("2026-11-07"), asOf))  // and it is not past either: the split is a partition
        assertTrue(CampRules.isPast(camp("2026-11-01"), asOf))
    }

    @Test
    fun `camps sort ascending by date`() {
        val camps = listOf(camp("2026-12-01"), camp("2026-10-01"), camp("2026-11-15"))
        val sorted = CampRules.sortByDate(camps)
        assertEquals(listOf("2026-10-01", "2026-11-15", "2026-12-01"),
            sorted.map { it.campDate })
    }
}
