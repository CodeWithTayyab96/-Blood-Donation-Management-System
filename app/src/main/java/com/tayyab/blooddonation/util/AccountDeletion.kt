package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Delete my account" — the client half.
 *
 * The account itself can only be deleted by the `delete-account` Edge Function,
 * because deleting an Auth user needs the service role key, and that key must
 * never reach a phone. This class only calls it and reads the answer.
 *
 * The answer has three shapes that matter, and the difference is what the user
 * is told:
 *
 *  - `deleted`  — everything is gone.
 *  - `partial`  — the personal data is gone but the login still exists (step 2
 *    of the function failed). The account still works, and pressing the button
 *    again finishes the job: the function is idempotent.
 *  - `refused`  — nothing happened (manager account, bad session, no network,
 *    function not deployed). The account is untouched and the user keeps it.
 */
object AccountDeletion {

    sealed class Result {
        /** The account and its personal data are gone. */
        object Deleted : Result()

        /** Personal data gone, login still exists. Retryable and safe. */
        data class Partial(val reason: String) : Result()

        /** Nothing was deleted. [reason] is safe to show and safe to screenshot. */
        data class Refused(val reason: String) : Result()
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_DETAIL_LENGTH = 120

    private val FLAG_PARTIAL = Regex("\"dataDeleted\"\\s*:\\s*true")
    private val FIELD_ERROR = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val FIELD_DETAIL = Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun delete(): Result {
        val token = Session.accessToken
        if (token.isNullOrBlank()) {
            return Result.Refused("You are not signed in — there is no account to delete.")
        }
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            return Result.Refused("This build has no server configured.")
        }

        val (code, body) = call(token)
        return classify(code, body)
    }

    /**
     * The whole decision, with no I/O — so it can be unit-tested.
     *
     * Every branch has to end in something the user can act on or at least
     * understand. A branch that fell through to a bare "HTTP 500" would leave
     * them guessing whether their account still exists, which for this action
     * is the one thing they need to know.
     */
    internal fun classify(code: Int, body: String?): Result {
        val message = body?.let { readError(it) }
        return when {
            // -1 is our own marker for "the request never completed", which is
            // almost always no connection — not the server refusing anything.
            code < 0 -> Result.Refused(
                "No internet connection. Nothing was deleted — try again when you are online."
            )
            code in 200..299 -> Result.Deleted
            code == 403 -> Result.Refused(message ?: "Manager accounts cannot delete themselves in the app.")
            code == 401 -> Result.Refused("Your sign-in has expired. Sign in again and try once more.")
            // The function is not deployed (or the URL is wrong): the platform
            // answers with a plain-text page, which must never be shown raw.
            code == 404 -> Result.Refused(
                "The account-deletion service is not set up on this project yet. " +
                    "Nothing was deleted."
            )
            code == 503 -> Result.Refused("The account-deletion service is not configured on this project.")
            isPartial(body) -> Result.Partial(
                message ?: "Your data was deleted, but the login still exists. Try once more."
            )
            else -> Result.Refused(message ?: "The server refused (HTTP $code). Nothing was deleted.")
        }
    }

    /** True when the function reports that step 1 succeeded but step 2 did not. */
    private fun isPartial(body: String?): Boolean =
        body != null && FLAG_PARTIAL.containsMatchIn(body)

    /**
     * Pulls the server's `"error"` (and, when short, its `"detail"`) out of the
     * response by hand.
     *
     * Deliberately not `org.json`: JSON handling here has to be unit-testable,
     * and the JVM test classpath has no real JSON library — the Android one is
     * stubbed out and answers every call with a default, which would quietly
     * turn every failure into "no message". The responses are flat, so a
     * regex is honest and cannot lie the way a stub can.
     */
    private fun readError(body: String): String? {
        val error = FIELD_ERROR.find(body)?.groupValues?.get(1)?.unescape()
            ?.takeIf { it.isNotBlank() }
        val detail = FIELD_DETAIL.find(body)?.groupValues?.get(1)?.unescape()
            ?.takeIf { it.isNotBlank() }
        // `detail` is the database's own wording. It earns its place only when
        // it is short enough to read and does not look like a stack trace.
        if (error == null) return null
        return if (detail != null && detail.length <= MAX_DETAIL_LENGTH) "$error ($detail)" else error
    }

    private fun String.unescape(): String = this
        .replace("\\n", " ")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .trim()

    private fun call(token: String): Pair<Int, String?> {
        return try {
            val connection = URL(url()).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                // An empty body: the function takes no arguments on purpose —
                // the account to delete comes from the token, not from here.
                connection.doOutput = true
                connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            -1 to null
        } catch (_: Exception) {
            -1 to null
        }
    }

    private fun url(): String =
        BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/delete-account"
}
