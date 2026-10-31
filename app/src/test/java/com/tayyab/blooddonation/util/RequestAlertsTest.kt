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

    private fun request(group: String, status: String = "Pending") = BloodRequest(
        id = 1,
        patientName = "Test Patient",
        hospital = "Test Hospital",
        bloodGroup = group,
        unitsNeeded = 2,
        urgency = "Urgent",
        contact = "0000-0000000",
        status = status,
        createdAt = "2026-10-31"
    )

    @Test
    fun `compatible pending request matches`() {
        assertTrue(RequestAlerts.matches(donor("O-"), request("A+")))
    }

    @Test
    fun `incompatible request does not match`() {
        assertFalse(RequestAlerts.matches(donor("A+"), request("O-")))
    }

    @Test
    fun `fulfilled request does not match`() {
        assertFalse(RequestAlerts.matches(donor("O-"), request("A+", status = "Fulfilled")))
    }

    @Test
    fun `only the correct direction matches`() {
        // O+ can give to A+, but A+ cannot give to O+.
        assertTrue(RequestAlerts.matches(donor("O+"), request("A+")))
        assertFalse(RequestAlerts.matches(donor("A+"), request("O+")))
    }

    @Test
    fun `a donor is never alerted for their own group only`() {
        // AB+ can only give to AB+; it must not match any other patient group.
        val abPositive = donor("AB+")
        assertTrue(RequestAlerts.matches(abPositive, request("AB+")))
        for (other in BloodCompatibility.ALL_GROUPS - "AB+") {
            assertFalse("AB+ should not match $other", RequestAlerts.matches(abPositive, request(other)))
        }
    }
}
