package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Minimal Supabase Auth (GoTrue) client, in the same style as
 * [SupabaseClient]: plain [HttpURLConnection] + org.json, no new
 * library dependency.
 *
 * IMPORTANT THREADING RULE: every function here does network I/O and
 * blocks until it returns, so callers MUST run it on a background
 * thread (see [RegisterActivity] / [LoginActivity]) and post results
 * back with runOnUiThread. Nothing in this file ever touches the UI
 * thread itself.
 *
 * IMPORTANT FAILURE RULE: no function here ever throws. A failed call
 * returns null and records a short note in [lastError]. A network
 * failure (no connection, timeout, DNS) is reported as exactly
 * "No internet connection", so callers can tell "offline" apart from
 * "wrong email/password" or "email already registered".
 *
 * What the server returns, and what we do with it:
 * - signUp / signIn / refresh return a session: an access_token (a
 *   JWT, sent as the Bearer token on every PostgREST call while it is
 *   valid), a refresh_token (used once, automatically, when the access
 *   token expires — see [SupabaseClient]), and the user's id.
 * - If the project still had "Confirm email" ON, signUp would return a
 *   user but NO session. That is treated as a failure here, with a
 *   message saying confirmation is required — the app never pretends
 *   such a sign-up succeeded. (The project runs with Confirm email
 *   OFF, so sign-up normally returns a session straight away.)
 *
 * The anon key is never written in code: it arrives as the BuildConfig
 * field SUPABASE_ANON_KEY, exactly as in [SupabaseClient]. With an
 * empty key the client reports [isConfigured] = false and every call
 * returns null immediately.
 */
object SupabaseAuth {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** One signed-in session, or null when there is none. */
    data class AuthResult(
        val userId: String,
        val accessToken: String,
        val refreshToken: String
    )

    /** True only when a project URL and a non-empty anon key were baked in. */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** Short note about the most recent failure, for debugging and toasts. */
    var lastError: String? = null
        private set

    private fun authUrl(): String = BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1"

    /**
     * One raw Auth call. Returns (HTTP status, response body or null).
     * Status -1 means the request never completed (no network, timeout);
     * that case sets [lastError] to "No internet connection".
     */
    private fun post(path: String, body: JSONObject): Pair<Int, String?> {
        if (!isConfigured) {
            lastError = "Supabase is not configured"
            return -1 to null
        }
        return try {
            val connection = URL(authUrl() + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (code !in 200..299) {
                    lastError = errorMessage(code, text) ?: "Supabase Auth -> HTTP $code"
                }
                code to text
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            lastError = "No internet connection"
            -1 to null
        } catch (e: Exception) {
            lastError = "Request failed: ${e.javaClass.simpleName}"
            -1 to null
        }
    }

    /**
     * Turns a GoTrue error body into a sentence the user can act on.
     *
     * "Invalid login credentials" and "Email not confirmed" are the server's
     * words, not an explanation; and an unrecognised failure used to be
     * reported as nothing at all. Unknown cases keep the server's own message
     * and add the status code, so a screenshot is enough to diagnose it.
     */
    private fun errorMessage(code: Int, body: String?): String? {
        val raw = if (body.isNullOrBlank()) null else try {
            val obj = JSONObject(body)
            obj.optString("msg", "").takeIf { it.isNotBlank() }
                ?: obj.optString("error_description", "").takeIf { it.isNotBlank() }
                ?: obj.optString("error", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val lower = raw?.lowercase() ?: ""
        return when {
            lower.contains("invalid login credentials") || lower.contains("invalid_credentials") ->
                "Wrong email or password."
            lower.contains("email not confirmed") || lower.contains("email_not_confirmed") ->
                "Email not confirmed. Check your inbox and tap the link, then sign in again."
            lower.contains("rate limit") || lower.contains("too many") || code == 429 ->
                "Too many attempts. Wait a minute and try again."
            lower.contains("already registered") || lower.contains("user_already_exists") ->
                "That email is already registered. Sign in instead."
            lower.contains("weak password") || lower.contains("weak_password") ->
                "That password is too weak — use at least 6 characters."
            lower.contains("signups not allowed") ->
                "Sign-ups are turned off on this project."
            raw != null -> "Server rejected the request (HTTP $code): $raw"
            else -> "Server rejected the request (HTTP $code)."
        }
    }

    /**
     * Asks Supabase to send the sign-up confirmation email again.
     * GoTrue answers 200 even when it decides not to send (to avoid leaking
     * which addresses exist), so a true result means "accepted", not
     * "delivered".
     */
    fun resendConfirmation(email: String): Boolean {
        lastError = null
        val (code, body) = post("/resend", JSONObject().apply {
            put("type", "signup")
            put("email", email)
        })
        if (code in 200..299) return true
        lastError = errorMessage(code, body) ?: lastError
        return false
    }

    /**
     * Turns a GoTrue session response into an [AuthResult]. Returns null
     * (with [lastError] set) when the response has no usable session —
     * which is also what happens when email confirmation is still ON:
     * the user exists, but no tokens are issued until they confirm.
     */
    private fun sessionFrom(body: String?): AuthResult? {
        if (body.isNullOrBlank()) {
            lastError = "Empty response from Supabase Auth"
            return null
        }
        return try {
            val obj = JSONObject(body)
            val access = obj.optString("access_token", "")
            val refresh = obj.optString("refresh_token", "")
            val userId = obj.optJSONObject("user")?.optString("id", "") ?: ""
            if (access.isBlank() || refresh.isBlank() || userId.isBlank()) {
                lastError = if (userId.isNotBlank()) {
                    "Email confirmation is required — check your inbox before signing in"
                } else {
                    // Plain words: the person holding the phone does not
                    // know what Supabase is. (signUp replaces this with
                    // "already registered" — see there.)
                    "Sign-in didn't return an account. Please try again."
                }
                null
            } else {
                lastError = null
                AuthResult(userId, access, refresh)
            }
        } catch (_: Exception) {
            lastError = "Unexpected response from Supabase Auth"
            null
        }
    }

    /** Creates a Supabase Auth user. Null on failure (see [lastError]). */
    fun signUp(email: String, password: String): AuthResult? {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val (code, response) = post("/signup", body)
        if (code !in 200..299) return null
        val result = sessionFrom(response)
        if (result == null) {
            // GoTrue answers 200 with no session when the email is already
            // registered: it never says so outright, so the account cannot
            // be probed. With "Confirm email" OFF a new sign-up always
            // returns a session straight away, so no session here can only
            // mean the email is taken. Say that in plain words instead of
            // quoting Supabase — the person holding the phone does not know
            // what Supabase is.
            lastError = "This email is already registered. Please sign in instead."
        }
        return result
    }

    /** Signs in with email + password. Null on failure (see [lastError]). */
    // ----------------------------------------------------- password recovery

    /**
     * Asks Supabase to send a recovery email.
     *
     * The email must contain the 6-digit code (`{{ .Token }}` in the template —
     * see RUNBOOK), because the app asks the user to type it back. GoTrue
     * answers 200 even when it decides not to send, so `true` means "accepted",
     * not "delivered".
     */
    fun recover(email: String): Boolean {
        lastError = null
        val (code, body) = post("/recover", JSONObject().apply { put("email", email) })
        if (code in 200..299) return true
        lastError = errorMessage(code, body) ?: lastError
        return false
    }

    /**
     * Exchanges the 6-digit code for a session. On success the caller holds a
     * real access token, which is the only thing allowed to set a new password.
     */
    fun verifyRecovery(email: String, code: String): AuthResult? {
        lastError = null
        val (status, body) = post("/verify", JSONObject().apply {
            put("type", "recovery")
            put("email", email)
            put("token", code)
        })
        if (status !in 200..299) {
            lastError = errorMessage(status, body)
                ?: "That code is not valid any more. Ask for a new email."
            return null
        }
        return sessionFrom(body)
    }

    /** Sets a new password for the session [accessToken] belongs to. */
    fun updatePassword(accessToken: String, newPassword: String): Boolean {
        lastError = null
        val (code, body) = send(
            "PUT", "/user",
            JSONObject().apply { put("password", newPassword) },
            accessToken
        )
        if (code in 200..299) return true
        lastError = errorMessage(code, body) ?: lastError
        return false
    }

    /** One raw Auth call with an explicit method and an optional bearer token. */
    private fun send(
        method: String,
        path: String,
        body: JSONObject,
        token: String? = null
    ): Pair<Int, String?> {
        if (!isConfigured) {
            lastError = "Supabase is not configured"
            return -1 to null
        }
        return try {
            val connection = URL(authUrl() + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                if (!token.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                connection.doOutput = true
                connection.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (code !in 200..299) {
                    lastError = errorMessage(code, text) ?: "Supabase Auth -> HTTP $code"
                }
                code to text
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            lastError = "No internet connection"
            -1 to null
        } catch (e: Exception) {
            lastError = "Request failed: ${e.javaClass.simpleName}"
            -1 to null
        }
    }

    fun signIn(email: String, password: String): AuthResult? {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val (code, response) = post("/token?grant_type=password", body)
        if (code !in 200..299) return null
        return sessionFrom(response)
    }

    /**
     * Exchanges a refresh token for a fresh session. Null on failure
     * (see [lastError]). Used by [SupabaseClient] when an access token
     * expires mid-use; it never touches PostgREST itself, so it cannot
     * loop back into a refresh.
     */
    fun refreshSession(refreshToken: String): AuthResult? {
        val body = JSONObject().apply {
            put("refresh_token", refreshToken)
        }
        val (code, response) = post("/token?grant_type=refresh_token", body)
        if (code !in 200..299) return null
        return sessionFrom(response)
    }

    /**
     * Revokes the current Auth session on the server. Called from
     * [Session.logout] on a background thread, after the local state has
     * already been cleared — so this never throws and its result is only
     * recorded in [lastError]. A failure here means the refresh token stays
     * valid server-side until it expires; the device is signed out either way.
     */
    fun signOut(accessToken: String) {
        if (!isConfigured || accessToken.isBlank()) return
        try {
            val connection =
                URL(authUrl() + "/logout").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { out ->
                    out.write("{}".toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                // Drain the body so the socket is released.
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (code !in 200..299) {
                    lastError = "Supabase Auth logout -> HTTP $code"
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            lastError = "No internet connection"
        } catch (e: Exception) {
            lastError = "Logout request failed: ${e.javaClass.simpleName}"
        }
    }

    /**
     * The donor profile linked to an Auth user id, or null if there is
     * none (or it could not be fetched). This goes through
     * [SupabaseClient]'s authenticated request path, so it carries the
     * signed-in user's token and its 401-refresh behaviour.
     */
    fun getDonorByAuthId(authId: String): com.tayyab.blooddonation.model.Donor? {
        return SupabaseClient.getDonorByAuthId(authId)
    }
}
