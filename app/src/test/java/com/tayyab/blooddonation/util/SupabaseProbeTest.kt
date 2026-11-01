package com.tayyab.blooddonation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Check 2 asks "is the server reachable?", not "did this request succeed?".
 *
 * The old code called `GET /rest/v1/`, which Supabase now restricts to the
 * service_role key, so a perfectly healthy server looked broken with HTTP 401.
 * Any HTTP answer must therefore count as reachable; only a request that never
 * completes (status -1) is a failure.
 *
 * The screen also has to stay calm: the headline is the plain word
 * "reachable", and the status code travels as small print (Probe.note).
 */
class SupabaseProbeTest {

    @Test
    fun `any HTTP answer is reachable and keeps the code as small print`() {
        for (code in listOf(200, 204, 301, 400, 401, 403, 404, 500, 503)) {
            val probe = SupabaseClient.probeReachable(code)
            assertTrue("HTTP $code should count as reachable", probe.ok)
            assertEquals("reachable", probe.detail)
            assertEquals("HTTP $code", probe.note)
        }
    }

    /** The exact regression: 401 must no longer read as "unreachable". */
    @Test
    fun `401 is reachable and keeps 401 out of the headline`() {
        val probe = SupabaseClient.probeReachable(401)
        assertTrue(probe.ok)
        assertEquals("reachable", probe.detail)
        assertEquals("HTTP 401", probe.note)
    }

    @Test
    fun `no HTTP answer is unreachable`() {
        // -1 is what the client returns when the request never completed.
        val timedOut = SupabaseClient.probeReachable(-1)
        assertFalse(timedOut.ok)
        assertNull(timedOut.note)

        val neverAnswered = SupabaseClient.probeReachable(0)
        assertFalse(neverAnswered.ok)
        assertNull(neverAnswered.note)
    }
}
