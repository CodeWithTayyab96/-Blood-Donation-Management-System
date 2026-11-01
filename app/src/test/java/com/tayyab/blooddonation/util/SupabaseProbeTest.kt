package com.tayyab.blooddonation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Check 2 asks "is the server reachable?", not "did this request succeed?".
 *
 * The old code called `GET /rest/v1/`, which Supabase now restricts to the
 * service_role key, so a perfectly healthy server looked broken with HTTP 401.
 * Any HTTP answer must therefore count as reachable; only a request that never
 * completes (status -1) is a failure.
 */
class SupabaseProbeTest {

    @Test
    fun `any HTTP answer is reachable and shows the status code`() {
        for (code in listOf(200, 204, 301, 400, 401, 403, 404, 500, 503)) {
            val probe = SupabaseClient.probeReachable(code)
            assertTrue("HTTP $code should count as reachable", probe.ok)
            assertEquals("server answered HTTP $code", probe.detail)
        }
    }

    /** The exact regression: 401 must no longer read as "unreachable". */
    @Test
    fun `401 is reachable and reports 401`() {
        val probe = SupabaseClient.probeReachable(401)
        assertTrue(probe.ok)
        assertEquals("server answered HTTP 401", probe.detail)
    }

    @Test
    fun `no HTTP answer is unreachable`() {
        // -1 is what the client returns when the request never completed.
        assertFalse(SupabaseClient.probeReachable(-1).ok)
        assertFalse(SupabaseClient.probeReachable(0).ok)
    }
}
