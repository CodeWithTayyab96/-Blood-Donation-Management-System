package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for decoding FCM data-only payloads (Phase 3 push).
 *
 * The parser is the only interesting pure logic on the push path: everything
 * else (Firebase init, token upload, notification posting) needs a device or
 * Robolectric. A malformed or unknown message must parse to null — never
 * throw — because it is decoded inside the notification service.
 */
class PushDataTest {

    @Test
    fun `urgent request parses into a Pending request`() {
        val payload = mapOf(
            "type" to "urgent_request",
            "request_id" to "42",
            "blood_group" to "O-",
            "urgency" to "Critical",
            "units_needed" to "2",
            "hospital" to "City Hospital",
            "contact" to "+92 300 1234567",
            "city" to "Faisalabad"
        )
        val data = PushParser.parse(payload)
        assertTrue(data is PushData.UrgentRequest)
        val request = (data as PushData.UrgentRequest).request
        assertEquals(42L, request.id)
        assertEquals("O-", request.bloodGroup)
        assertEquals("Critical", request.urgency)
        assertEquals(2, request.unitsNeeded)
        assertEquals("City Hospital", request.hospital)
        assertEquals("+92 300 1234567", request.contact)
        assertEquals("Faisalabad", request.city)
        // The patient's name is never sent or shown.
        assertEquals("", request.patientName)
        assertEquals("Pending", request.status)
    }

    @Test
    fun `urgent request defaults missing urgency and units`() {
        val data = PushParser.parse(
            mapOf("type" to "urgent_request", "request_id" to "7", "blood_group" to "B+")
        ) as PushData.UrgentRequest
        assertEquals("Urgent", data.request.urgency)
        assertEquals(1, data.request.unitsNeeded)
    }

    @Test
    fun `urgent request without group or id is unusable`() {
        assertNull(PushParser.parse(mapOf("type" to "urgent_request", "request_id" to "7")))
        assertNull(
            PushParser.parse(
                mapOf("type" to "urgent_request", "blood_group" to "AB+")
            )
        )
    }

    @Test
    fun `offer payload parses with ids and text`() {
        val data = PushParser.parse(
            mapOf(
                "type" to "offer",
                "request_id" to "12",
                "offer_id" to "3",
                "title" to "New offer",
                "body" to "Sara (B+) offered to donate"
            )
        )
        assertTrue(data is PushData.Offer)
        val offer = data as PushData.Offer
        assertEquals(12L, offer.requestId)
        assertEquals(3L, offer.offerId)
        assertEquals("Sara (B+) offered to donate", offer.body)
    }

    @Test
    fun `accepted payload parses as OfferAccepted`() {
        val data = PushParser.parse(
            mapOf("type" to "accepted", "request_id" to "12", "offer_id" to "3")
        )
        assertTrue(data is PushData.OfferAccepted)
        assertEquals("Your donation offer was accepted", (data as PushData.OfferAccepted).body)
    }

    @Test
    fun `offer falls back to a generic body when none was sent`() {
        val data = PushParser.parse(
            mapOf("type" to "offer", "request_id" to "12", "offer_id" to "3")
        ) as PushData.Offer
        assertTrue(data.body.isNotEmpty())
        assertTrue(data.title.isNotEmpty())
    }

    @Test
    fun `bad or missing ids make the message unusable`() {
        assertNull(
            PushParser.parse(
                mapOf("type" to "offer", "request_id" to "abc", "offer_id" to "3")
            )
        )
        assertNull(
            PushParser.parse(
                mapOf("type" to "offer", "request_id" to "12")
            )
        )
        assertNull(
            PushParser.parse(
                mapOf("type" to "offer", "request_id" to "0", "offer_id" to "3")
            )
        )
    }

    @Test
    fun `unknown and empty payloads are ignored`() {
        assertNull(PushParser.parse(emptyMap()))
        assertNull(PushParser.parse(mapOf("type" to "something_else")))
        assertNull(PushParser.parse(mapOf("body" to "no type at all")))
    }

    @Test
    fun `ids are whitespace and zero tolerant`() {
        val data = PushParser.parse(
            mapOf("type" to "offer", "request_id" to " 5 ", "offer_id" to " 6 ")
        ) as PushData.Offer
        assertEquals(5L, data.requestId)
        assertEquals(6L, data.offerId)
    }
}
