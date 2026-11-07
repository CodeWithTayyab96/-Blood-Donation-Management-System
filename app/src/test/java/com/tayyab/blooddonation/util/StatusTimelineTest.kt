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
}
