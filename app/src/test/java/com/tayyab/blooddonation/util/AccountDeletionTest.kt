package com.tayyab.blooddonation.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Delete my account" — the client's reading of the Edge Function's answer.
 *
 * These matter more than a normal response-parsing test, because every branch
 * decides whether the phone wipes its local data. Wiping on a refused deletion
 * would destroy the user's cached copy of an account that still exists; not
 * wiping on a real deletion would leave the app showing a donor that no longer
 * exists anywhere. The three outcomes must not be confusable.
 */
class AccountDeletionTest {

    @Test
    fun `200 means deleted`() {
        assertTrue(AccountDeletion.classify(200, """{"ok":true}""") is AccountDeletion.Result.Deleted)
    }

    @Test
    fun `data deleted but login left is partial, not success`() {
        val result = AccountDeletion.classify(
            500,
            """{"error":"your data was deleted but the login still exists","dataDeleted":true}"""
        )
        assertTrue(
            "a half-deleted account must not be reported as deleted (the local data is gone, but the user is still signed in)",
            result is AccountDeletion.Result.Partial
        )
    }

    @Test
    fun `a manager is refused and nothing is wiped`() {
        val result = AccountDeletion.classify(
            403,
            """{"error":"manager accounts cannot delete themselves from the app"}"""
        )
        assertTrue(result is AccountDeletion.Result.Refused)
        assertTrue((result as AccountDeletion.Result.Refused).reason.contains("Manager", ignoreCase = true))
    }

    @Test
    fun `an expired session is refused`() {
        val result = AccountDeletion.classify(401, """{"error":"invalid or expired session"}""")
        assertTrue(result is AccountDeletion.Result.Refused)
    }

    @Test
    fun `a missing function is refused with an explanation, not a status code`() {
        // The platform returns a non-JSON page for an undeployed function.
        val result = AccountDeletion.classify(404, "404 Not Found")
        assertTrue(result is AccountDeletion.Result.Refused)
        assertTrue((result as AccountDeletion.Result.Refused).reason.contains("not set up", ignoreCase = true))
    }

    @Test
    fun `no connection is refused, and says nothing was deleted`() {
        val result = AccountDeletion.classify(-1, null)
        assertTrue(result is AccountDeletion.Result.Refused)
        val reason = (result as AccountDeletion.Result.Refused).reason
        assertTrue(reason.contains("No internet", ignoreCase = true))
        assertTrue("the user must be told their account is intact", reason.contains("Nothing was deleted"))
    }

    @Test
    fun `any other failure is refused, never partial`() {
        for (code in listOf(400, 500, 502, 503)) {
            val result = AccountDeletion.classify(code, null)
            assertTrue("HTTP $code must not be treated as a deletion", result is AccountDeletion.Result.Refused)
        }
    }

    @Test
    fun `an empty or broken body still produces a usable message`() {
        for (body in listOf(null, "", "not json at all", "<html>")) {
            val result = AccountDeletion.classify(500, body)
            assertTrue(result is AccountDeletion.Result.Refused)
            assertTrue((result as AccountDeletion.Result.Refused).reason.isNotBlank())
        }
    }
}
