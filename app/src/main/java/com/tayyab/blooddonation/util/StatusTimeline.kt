package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.StatusEvent

/**
 * Pure rules for the request status timeline.
 *
 * Status VALUES are deliberately the app's existing ones (Pending / Matched /
 * Fulfilled / Cancelled / Expired) — no new vocabulary was introduced, so the
 * RPCs, the offers CHECK constraint and older APKs keep working. The screen
 * translates them to friendly labels through strings.xml.
 */
object StatusTimeline {

    val TERMINAL = setOf("Fulfilled", "Cancelled", "Expired")

    fun isTerminal(status: String): Boolean = status in TERMINAL

    /** Oldest first; ties broken by id so a re-render never reshuffles rows. */
    fun ordered(events: List<StatusEvent>): List<StatusEvent> =
        events.sortedWith(compareBy<StatusEvent> { it.changedAt }.thenBy { it.id })
            // A transition recorded locally and then again by the server
            // trigger is ONE step, not two: completing a request locally
            // writes a row (actor "requester"), and the online PATCH then
            // fires the server trigger, which writes a second row for the
            // same status (actor "server"). Collapse consecutive repeats of
            // the same status, keeping the later row (the server one). A
            // status the request genuinely returns to later is not
            // consecutive, so it still shows twice, as it should.
            .fold(mutableListOf<StatusEvent>()) { acc, e ->
                if (acc.isEmpty() ||
                    !acc.last().status.equals(e.status, ignoreCase = true)
                ) {
                    acc.add(e)
                } else {
                    acc[acc.lastIndex] = e
                }
                acc
            }

    /**
     * True when [status] is a real change from [previous]. A pull that returns
     * the same status again must not append a duplicate timeline row.
     */
    fun isChange(previous: String?, status: String): Boolean =
        status.isNotBlank() && !status.equals(previous, ignoreCase = true)
}
