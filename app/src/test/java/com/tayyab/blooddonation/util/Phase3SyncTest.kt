package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure Phase 3 sync rules: the booking-failure mapping and the
 * caller-ownership guard. Both are network-free on purpose — the
 * mapping only reads the server's error text, and the guard only reads
 * ids — so they run on the plain JVM test classpath.
 *
 * The SQLite upserts (upsert-by-id keeps local-only rows, the shadow
 * row replacement on a UNIQUE conflict) cannot be covered here: they
 * need a real SQLiteDatabase, which the JVM test classpath does not
 * provide. That behaviour needs an on-device run to verify.
 */
class Phase3SyncTest {

    @Test
    fun `a full slot maps to Full`() {
        assertEquals(
            SupabaseSync.BookingOutcome.Full,
            SupabaseSync.mapBookingFailure(
                "Supabase POST /appointment_bookings?select=id -> HTTP 409: slot is full"
            )
        )
    }

    @Test
    fun `a duplicate booking maps to Duplicate`() {
        assertEquals(
            SupabaseSync.BookingOutcome.Duplicate,
            SupabaseSync.mapBookingFailure(
                "Supabase POST /appointment_bookings?select=id -> HTTP 409: " +
                    "duplicate key value violates unique constraint " +
                    "\"appointment_bookings_slot_id_donor_id_key\""
            )
        )
    }

    @Test
    fun `any other refusal maps to Failed with the server words`() {
        val detail = "Supabase POST /appointment_bookings?select=id -> HTTP 403: " +
            "new row violates row-level security policy for table \"appointment_bookings\""
        val outcome = SupabaseSync.mapBookingFailure(detail)
        assertTrue(outcome is SupabaseSync.BookingOutcome.Failed)
        assertEquals(detail, (outcome as SupabaseSync.BookingOutcome.Failed).detail)
    }

    @Test
    fun `a missing reason still maps to Failed`() {
        val outcome = SupabaseSync.mapBookingFailure(null)
        assertTrue(outcome is SupabaseSync.BookingOutcome.Failed)
        assertEquals(
            "the server did not accept the booking",
            (outcome as SupabaseSync.BookingOutcome.Failed).detail
        )
    }

    @Test
    fun `the full-slot wording is matched regardless of case`() {
        assertEquals(
            SupabaseSync.BookingOutcome.Full,
            SupabaseSync.mapBookingFailure("Slot Is Full")
        )
    }

    @Test
    fun `a donor may write only their own rows unless manager`() {
        assertTrue(SupabaseSync.mayWriteForDonor(7, 7, false))
        assertFalse(SupabaseSync.mayWriteForDonor(7, 9, false))
        assertFalse(SupabaseSync.mayWriteForDonor(7, null, false))
        assertTrue(SupabaseSync.mayWriteForDonor(7, 9, true))
        assertTrue(SupabaseSync.mayWriteForDonor(7, null, true))
    }
}
