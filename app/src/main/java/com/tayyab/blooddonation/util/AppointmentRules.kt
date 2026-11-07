package com.tayyab.blooddonation.util

/**
 * Phase 3: appointment-slot overbooking rules.
 *
 * The guard is enforced at the database level (a transaction that re-checks
 * capacity before inserting, and a Supabase trigger that rejects an insert past
 * capacity), so two donors clicking at once cannot both succeed. This object is
 * the pure, unit-tested copy of that rule that the UI also consults for messaging.
 */
object AppointmentRules {

    /** True once booked >= capacity. */
    fun isFull(capacity: Int, booked: Int): Boolean = booked >= capacity

    sealed interface BookResult {
        /** Within capacity and not already booked by this donor. */
        object Ok : BookResult
        /** Capacity already reached — the DB guard would reject this. */
        object Full : BookResult
        /** This donor already holds a booking on the slot. */
        object Duplicate : BookResult
    }

    /**
     * The decision a booking attempt should produce. Mirrors exactly what the
     * DB transaction evaluates after it has locked the slot row.
     */
    fun evaluate(capacity: Int, booked: Int, alreadyBooked: Boolean): BookResult =
        if (alreadyBooked) BookResult.Duplicate
        else if (isFull(capacity, booked)) BookResult.Full
        else BookResult.Ok
}
