package com.tayyab.blooddonation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the P2P state machine.
 *
 * The two things most likely to be got wrong: treating `Matched` as closed
 * (it is not — an accepted offer has not been fulfilled yet), and letting
 * expiry resurrect a request that was already cancelled or fulfilled.
 */
class P2pRulesTest {

    // ------------------------------------------------------------- requests

    @Test
    fun `pending and matched are both open`() {
        assertTrue(P2pRules.isOpenRequest("Pending"))
        // Matched means someone offered and was accepted; nothing is donated yet.
        assertTrue(P2pRules.isOpenRequest("Matched"))
    }

    @Test
    fun `fulfilled, cancelled and expired are terminal`() {
        for (status in listOf("Fulfilled", "Cancelled", "Expired")) {
            assertTrue("$status should be terminal", P2pRules.isTerminalRequest(status))
            assertFalse("$status should not be open", P2pRules.isOpenRequest(status))
        }
    }

    @Test
    fun `a request with no needed-by date never expires`() {
        assertEquals(
            "Pending",
            P2pRules.requestStatusOnRead("Pending", null, "2026-10-31")
        )
        assertEquals(
            "Pending",
            P2pRules.requestStatusOnRead("Pending", "", "2026-10-31")
        )
    }

    @Test
    fun `an open request past its needed-by date is expired on read`() {
        assertEquals(
            "Expired",
            P2pRules.requestStatusOnRead("Pending", "2026-10-30", "2026-10-31")
        )
    }

    @Test
    fun `a request needed today is not yet expired`() {
        // "Needed by today" must still be open — the day is not over.
        assertEquals(
            "Pending",
            P2pRules.requestStatusOnRead("Pending", "2026-10-31", "2026-10-31")
        )
    }

    @Test
    fun `expiry never resurrects a terminal request`() {
        // The bug this guards: flipping a cancelled or fulfilled request to
        // Expired because its date has passed would reopen closed work.
        for (status in listOf("Cancelled", "Fulfilled")) {
            assertEquals(
                status,
                P2pRules.requestStatusOnRead(status, "2020-01-01", "2026-10-31")
            )
        }
    }

    @Test
    fun `expiry applies to matched requests too`() {
        assertEquals(
            "Expired",
            P2pRules.requestStatusOnRead("Matched", "2026-10-29", "2026-10-31")
        )
    }

    // --------------------------------------------------------------- offers

    @Test
    fun `an offer can only be accepted or declined while it is offered`() {
        assertTrue(P2pRules.canAcceptOffer("Offered"))
        assertTrue(P2pRules.canDeclineOffer("Offered"))
        for (status in listOf("Accepted", "Declined", "Completed")) {
            assertFalse("$status must not be accept-able", P2pRules.canAcceptOffer(status))
            assertFalse("$status must not be decline-able", P2pRules.canDeclineOffer(status))
        }
    }

    @Test
    fun `declined and completed offers are terminal`() {
        assertTrue(P2pRules.isTerminalOffer("Declined"))
        assertTrue(P2pRules.isTerminalOffer("Completed"))
        // Accepted is NOT terminal: it is waiting on both sides to confirm.
        assertFalse(P2pRules.isTerminalOffer("Accepted"))
        assertFalse(P2pRules.isTerminalOffer("Offered"))
    }

    // ----------------------------------------------------------- completion

    @Test
    fun `a donation is recorded only once both sides confirm`() {
        assertFalse(P2pRules.bothConfirmed("2026-10-31", null))
        assertFalse(P2pRules.bothConfirmed(null, "2026-10-31"))
        assertFalse(P2pRules.bothConfirmed(null, null))
        assertTrue(P2pRules.bothConfirmed("2026-10-31", "2026-10-31"))
    }

    @Test
    fun `blank confirmations do not count as confirmed`() {
        assertFalse(P2pRules.bothConfirmed("", "2026-10-31"))
        assertFalse(P2pRules.bothConfirmed("   ", "2026-10-31"))
    }

    // ------------------------------------------------------------------ cap

    @Test
    fun `the open-request cap is three`() {
        assertEquals(3, P2pRules.MAX_OPEN_REQUESTS)
    }
}
