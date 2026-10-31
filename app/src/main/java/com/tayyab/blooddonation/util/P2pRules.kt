package com.tayyab.blooddonation.util

/**
 * P2P: the request and offer status rules, kept in one pure place.
 *
 * These were previously inline string comparisons scattered across the
 * activities, which made them impossible to test and easy to get subtly
 * wrong (a request that is `Matched` is still open; a request past its
 * needed-by date and still open is `Expired`, but only when read, because
 * there is no pg_cron to flip it server-side).
 *
 * Nothing here touches Android, SQLite or the network, so it is unit-testable.
 */
object P2pRules {

    /** Hard cap on how many open requests one person may have at a time.
     *  The server enforces it too, with a BEFORE INSERT trigger. */
    const val MAX_OPEN_REQUESTS = 3

    // Requests
    const val PENDING = "Pending"
    const val MATCHED = "Matched"
    const val FULFILLED = "Fulfilled"
    const val CANCELLED = "Cancelled"
    const val EXPIRED = "Expired"

    // Offers
    const val OFFERED = "Offered"
    const val ACCEPTED = "Accepted"
    const val DECLINED = "Declined"
    const val COMPLETED = "Completed"

    private val OPEN_REQUESTS = setOf(PENDING, MATCHED)
    private val TERMINAL_REQUESTS = setOf(FULFILLED, CANCELLED, EXPIRED)
    private val TERMINAL_OFFERS = setOf(DECLINED, COMPLETED)

    /** Matched counts as open: an accepted offer has not been fulfilled yet. */
    fun isOpenRequest(status: String): Boolean = status in OPEN_REQUESTS

    /** Fulfilled / Cancelled / Expired — nothing further can happen. */
    fun isTerminalRequest(status: String): Boolean = status in TERMINAL_REQUESTS

    fun isTerminalOffer(status: String): Boolean = status in TERMINAL_OFFERS

    /** An offer can only be accepted or declined while it is still Offered. */
    fun canAcceptOffer(status: String): Boolean = status == OFFERED
    fun canDeclineOffer(status: String): Boolean = status == OFFERED

    /**
     * Lazy expiry. There is no pg_cron, so expiry is evaluated when the row is
     * read rather than written: a request that is still open but whose
     * needed-by date has passed is reported as Expired.
     *
     * A request with no needed-by date never expires. A request that is
     * already in a terminal state is returned unchanged — expiry must not
     * resurrect a cancelled or fulfilled request.
     *
     * [today] is injected rather than read from the clock so this is testable.
     */
    fun requestStatusOnRead(status: String, neededBy: String?, today: String): String {
        if (!isOpenRequest(status)) return status
        val needed = neededBy?.takeIf { it.isNotBlank() } ?: return status
        // Both are ISO yyyy-MM-dd, so lexicographic order is date order.
        return if (needed < today) EXPIRED else status
    }

    /** A peer donation is only recorded once BOTH sides say it happened. */
    fun bothConfirmed(donorAt: String?, requesterAt: String?): Boolean =
        !donorAt.isNullOrBlank() && !requesterAt.isNullOrBlank()
}
