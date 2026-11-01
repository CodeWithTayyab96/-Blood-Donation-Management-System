package com.tayyab.blooddonation.util

import android.util.Base64
import org.json.JSONObject

/**
 * Reads claims out of a Supabase access token.
 *
 * The token is **not** verified here — the server verifies it on every request,
 * and the client only uses the claims to decide which screens to show. A forged
 * token would achieve nothing: every table is behind RLS, which reads the same
 * claim server-side. This is a display decision, not a security boundary.
 *
 * The role is read from `app_metadata`, which only the service role can write.
 * `user_metadata` is user-editable and is never consulted — a user could set
 * `user_metadata.role = MANAGER` themselves.
 */
object Jwt {

    /** The decoded payload, or null if [token] is not a JWT this app can read. */
    fun claims(token: String?): JSONObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** `app_metadata.role`, or null when the token carries none. */
    fun role(token: String?): String? =
        claims(token)?.optJSONObject("app_metadata")
            ?.optString("role")
            ?.takeIf { it.isNotBlank() }

    /** `sub` — the Auth user id. */
    fun subject(token: String?): String? =
        claims(token)?.optString("sub")?.takeIf { it.isNotBlank() }

    /** Expiry in epoch milliseconds, or null when the claim is absent. */
    fun expiresAtMs(token: String?): Long? =
        claims(token)?.optLong("exp", 0L)?.takeIf { it > 0 }?.times(1000)
}
