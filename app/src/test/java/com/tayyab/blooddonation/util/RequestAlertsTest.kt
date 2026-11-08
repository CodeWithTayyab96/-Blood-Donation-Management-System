package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Donor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision the alert worker makes per request: should this donor be told
 * about it? Only [RequestAlerts.matches] is tested — the notification and the
 * polling need a device.
 */
class RequestAlertsTest {

    private fun donor(group: String) = Donor(
        name = "Test Donor",
        age = 30,
        gender = "Male",
        bloodGroup = group,
        phone = "0000-0000000",
        city = "Testville",
        weightKg = 70.0,
        lastDonationDate = null
    )

    private fun request(
        group: String,
        status: String = "Pending",
        urgency: String = "Urgent"
    ) = BloodRequest(
        id = 1,
        patientName = "Test Patient",
        hospital = "Test Hospital",
        bloodGroup = group,
        unitsNeeded = 2,
        urgency = urgency,
        contact = "0000-0000000",
        status = status,
        createdAt = "2026-10-31"
    )

    @Test
    fun `compatible pending request matches`() {
        assertTrue(RequestAlerts.matches(donor("O-"), request("A+")))
    }

    @Test
    fun `incompatible request still matches`() {
        // Everyone is alerted, not just the matching groups: a donor who
        // cannot give may still know someone off the app who can.
        assertTrue(RequestAlerts.matches(donor("A+"), request("O-")))
    }

    @Test
    fun `fulfilled request does not match`() {
        assertFalse(RequestAlerts.matches(donor("O-"), request("A+", status = "Fulfilled")))
    }

    @Test
    fun `normal urgency is not alerted`() {
        assertFalse(RequestAlerts.matches(donor("O-"), request("A+", urgency = "Normal")))
    }

    @Test
    fun `urgent and critical urgency are alerted`() {
        assertTrue(RequestAlerts.matches(donor("O-"), request("A+", urgency = "Urgent")))
        assertTrue(RequestAlerts.matches(donor("O-"), request("A+", urgency = "Critical")))
    }

    @Test
    fun `normal is rejected even when everything else matches`() {
        // Pending, same group, an eligible donor — urgency alone rules it out.
        assertFalse(RequestAlerts.matches(donor("O-"), request("O-", urgency = "Normal")))
    }

    @Test
    fun `compatibility direction no longer matters`() {
        // O+ can give to A+, and A+ cannot give to O+ — but both donors are
        // alerted either way, so either can pass the word on.
        assertTrue(RequestAlerts.matches(donor("O+"), request("A+")))
        assertTrue(RequestAlerts.matches(donor("A+"), request("O+")))
    }

    @Test
    fun `every group matches every request`() {
        // AB+ can only give to AB+, but the alert still reaches them for
        // every other group too.
        val abPositive = donor("AB+")
        for (other in BloodCompatibility.ALL_GROUPS) {
            assertTrue("AB+ should match $other", RequestAlerts.matches(abPositive, request(other)))
        }
    }
}
