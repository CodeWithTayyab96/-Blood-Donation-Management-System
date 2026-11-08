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
        // Real dates: the old fixture built "2026-11-32" .. "2026-11-50",
        // which are not dates at all (harmless here only because the
        // comparison is a plain string sort).
        val base = java.time.LocalDate.of(2026, 9, 1)
        val list = (1..50).map {
            req("A+", 1, "L", "H", "Urgent", base.plusDays(it.toLong()).toString())
        }
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

    @Test
    fun `queued request becomes a postable emergency request`() {
        val p = PendingRequest("A+", 2, "Faisalabad", "Allied", "Urgent", "2026-11-07")
        val req = EmergencyLogic.toRequest(p, "auth-1", "0300-1234567", "2026-11-08")
        assertEquals("Emergency patient", req.patientName)
        assertEquals("A+", req.bloodGroup)
        assertEquals(2, req.unitsNeeded)
        assertEquals("Faisalabad", req.city)
        assertEquals("Allied", req.hospital)
        assertEquals("Urgent", req.urgency)
        assertEquals("0300-1234567", req.contact)
        assertEquals("Pending", req.status)
        assertEquals("auth-1", req.requesterId)
        // Created on the day it is actually posted, not the day it was queued.
        assertEquals("2026-11-08", req.createdAt)
    }

    @Test
    fun `queued request without a known phone still posts`() {
        val p = PendingRequest("O-", 1, "Lahore", "H1", "Critical", "2026-11-08")
        val req = EmergencyLogic.toRequest(p, "auth-1", "", "2026-11-08")
        assertEquals("", req.contact)
        assertEquals("O-", req.bloodGroup)
    }
}
