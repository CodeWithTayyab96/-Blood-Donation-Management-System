package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class TipScheduleTest {

    private val today = 19_000L
    private val yesterday = today - 1

    @Test
    fun `disabled tips never send`() {
        assertFalse(TipSchedule.shouldSend(false, 12, null, today))
    }

    @Test
    fun `no tip before 9am`() {
        assertFalse(TipSchedule.shouldSend(true, 8, null, today))
    }

    @Test
    fun `no tip at or after 9pm`() {
        assertFalse(TipSchedule.shouldSend(true, 21, null, today))
        assertFalse(TipSchedule.shouldSend(true, 23, null, today))
    }

    @Test
    fun `no second tip on the same day`() {
        assertFalse(TipSchedule.shouldSend(true, 12, today, today))
    }

    @Test
    fun `tip allowed in daytime window when none sent today`() {
        assertTrue(TipSchedule.shouldSend(true, 12, null, today))
        assertTrue(TipSchedule.shouldSend(true, 12, yesterday, today))
        assertTrue(TipSchedule.shouldSend(true, 9, null, today))
        assertTrue(TipSchedule.shouldSend(true, 20, null, today))
    }

    @Test
    fun `quiet hours are the documented bounds`() {
        assertEquals(9, TipSchedule.QUIET_UNTIL_HOUR)
        assertEquals(21, TipSchedule.QUIET_FROM_HOUR)
    }
}
