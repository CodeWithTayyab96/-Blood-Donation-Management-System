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
                    lastError = errorMessage(text) ?: "Supabase Auth -> HTTP $code"
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

    /** GoTrue error bodies carry "msg" (and sometimes "error_description"). */
    private fun errorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(body)
            obj.optString("msg", "").takeIf { it.isNotBlank() }
                ?: obj.optString("error_description", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
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
                    "Supabase Auth returned no session"
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
        return sessionFrom(response)
    }

    /** Signs in with email + password. Null on failure (see [lastError]). */
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
