package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class AppointmentRulesTest {

    @Test
    fun `slot is full at capacity`() {
        assertTrue(AppointmentRules.isFull(3, 3))
        assertFalse(AppointmentRules.isFull(3, 2))
    }

    @Test
    fun `evaluate within capacity and not booked is Ok`() {
        assertSame(AppointmentRules.BookResult.Ok,
            AppointmentRules.evaluate(capacity = 3, booked = 2, alreadyBooked = false))
    }

    @Test
    fun `evaluate at capacity is Full`() {
        assertSame(AppointmentRules.BookResult.Full,
            AppointmentRules.evaluate(capacity = 3, booked = 3, alreadyBooked = false))
    }

    @Test
    fun `evaluate when already booked is Duplicate`() {
        assertSame(AppointmentRules.BookResult.Duplicate,
            AppointmentRules.evaluate(capacity = 5, booked = 0, alreadyBooked = true))
    }
}
