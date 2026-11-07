package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.StatusEvent
import org.junit.Assert.*
import org.junit.Test

class StatusTimelineTest {

    @Test
    fun `the app keeps its existing terminal statuses`() {
        assertTrue(StatusTimeline.isTerminal("Fulfilled"))
        assertTrue(StatusTimeline.isTerminal("Cancelled"))
        assertTrue(StatusTimeline.isTerminal("Expired"))
        assertFalse(StatusTimeline.isTerminal("Pending"))
        assertFalse(StatusTimeline.isTerminal("Matched"))
    }

    @Test
    fun `events are ordered oldest first with a stable tie break`() {
        val a = StatusEvent(1, 7, "Pending", "2026-11-01")
        val b = StatusEvent(2, 7, "Matched", "2026-11-02")
        val c = StatusEvent(3, 7, "Fulfilled", "2026-11-02")
        assertEquals(listOf(a, b, c), StatusTimeline.ordered(listOf(c, a, b)))
    }

    @Test
    fun `a repeated status is not a change`() {
        assertTrue(StatusTimeline.isChange("Pending", "Matched"))
        assertFalse(StatusTimeline.isChange("Pending", "Pending"))
        assertFalse(StatusTimeline.isChange(null, ""))
    }

    @Test
    fun `the same transition recorded locally and by the server shows once`() {
        // Local row: the requester completed the request on the phone
        // (date-only stamp). Server row: the online PATCH fired the
        // trigger (full timestamptz, actor "server").
        val local = StatusEvent(11, 7, "Fulfilled", "2026-11-07", "requester")
        val server = StatusEvent(42, 7, "Fulfilled", "2026-11-07T07:05:00+00:00", "server")
        val pending = StatusEvent(10, 7, "Pending", "2026-11-01", "requester")
        val shown = StatusTimeline.ordered(listOf(server, pending, local))
        assertEquals(listOf(pending, server), shown)
    }

    @Test
    fun `a status genuinely returned to later still shows twice`() {
        val a = StatusEvent(1, 7, "Pending", "2026-11-01")
        val b = StatusEvent(2, 7, "Matched", "2026-11-02")
        val c = StatusEvent(3, 7, "Pending", "2026-11-03")
        assertEquals(listOf(a, b, c), StatusTimeline.ordered(listOf(c, a, b)))
    }
}
