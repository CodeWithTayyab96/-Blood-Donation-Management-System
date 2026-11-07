package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.BloodRequest
import org.junit.Assert.*
import org.junit.Test

class EmergencyLogicTest {

    private fun req(group: String, units: Int, city: String, hospital: String, urgency: String, created: String) =
        BloodRequest(
            id = 0, patientName = "X", hospital = hospital, bloodGroup = group,
            unitsNeeded = units, urgency = urgency, contact = "", status = "Pending",
            createdAt = created, city = city
        )

    @Test
    fun `offline view orders critical then urgent then newest`() {
        val list = listOf(
            req("A+", 1, "Lahore", "H1", "Urgent", "2026-11-01"),
            req("O-", 2, "Lahore", "H2", "Critical", "2026-10-01"),
            req("B+", 1, "Lahore", "H3", "Normal", "2026-11-05")
        )
        val shown = EmergencyLogic.cachedForOffline(list)
        assertEquals("O-", shown[0].bloodGroup)   // Critical wins regardless of date
        assertEquals("A+", shown[1].bloodGroup)   // Urgent before Normal
        assertEquals("B+", shown[2].bloodGroup)
    }

    @Test
    fun `offline view respects the limit`() {
        val list = (1..50).map { req("A+", 1, "L", "H", "Urgent", "2026-11-${it.toString().padStart(2, '0')}") }
        assertEquals(20, EmergencyLogic.cachedForOffline(list).size)
    }

    @Test
    fun `duplicate pending request is detected`() {
        val pending = listOf(
            PendingRequest("O-", 2, "Lahore", "H2", "Critical", "2026-11-07")
        )
        assertTrue(EmergencyLogic.isDuplicate(pending,
            PendingRequest("O-", 2, "Lahore", "H2", "Critical", "2026-11-08")))
        assertFalse(EmergencyLogic.isDuplicate(pending,
            PendingRequest("O-", 3, "Lahore", "H2", "Critical", "2026-11-08")))
    }
}
