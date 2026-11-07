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

    /**
     * True when [status] is a real change from [previous]. A pull that returns
     * the same status again must not append a duplicate timeline row.
     */
    fun isChange(previous: String?, status: String): Boolean =
        status.isNotBlank() && !status.equals(previous, ignoreCase = true)
}
