package com.tayyab.blooddonation.util

import android.content.Context
import java.security.MessageDigest

/**
 * The signed-in account for this session, kept in SharedPreferences.
 *
 * v1.4: a signed-in account can now also be a real Supabase Auth
 * session. When the user signs in through Supabase Auth, [saveAuth]
 * stores the Auth email, the Auth user id, and the access + refresh
 * tokens alongside the existing username / role / donor-id fields, so
 * [SupabaseClient] can send the user's own JWT as the Bearer token on
 * PostgREST calls (the "authenticated" RLS policies only accept those).
 * The local username / password hash still exists for the offline
 * fallback login, and [label()] works exactly as before.
 *
 * Tokens are kept in memory after [load] and written back to
 * SharedPreferences by [saveAuth] / [updateTokens] / [clearTokens].
 * [logout] clears everything, tokens included. Passwords are stored
 * only as a SHA-256 hash salted with the username, never in plain text.
 */
object Session {

    private const val PREFS = "blood_session"
    private const val KEY_USER = "username"
    private const val KEY_ROLE = "role"
    private const val KEY_DONOR_ID = "donor_id"
    private const val KEY_EMAIL = "auth_email"
    private const val KEY_AUTH_ID = "auth_user_id"
    private const val KEY_ACCESS = "auth_access_token"
    private const val KEY_REFRESH = "auth_refresh_token"
    private const val KEY_PROFILE_PENDING = "profile_pending_sync"

    const val ROLE_MANAGER = "MANAGER"
    const val ROLE_DONOR = "DONOR"

    fun hashPassword(username: String, password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$username:$password".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Local-only sign-in (the v1.3 path: seeded manager, or the offline
     * fallback). A local sign-in carries no Auth session, so any
     * previously stored Auth tokens are cleared with it — otherwise a
     * later online call would go out under the previous user's token.
     */
    fun save(context: Context, username: String, role: String, donorId: Long?) {
        _appContext = context.applicationContext
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_USER, username)
            .putString(KEY_ROLE, role)
            .putLong(KEY_DONOR_ID, donorId ?: -1L)
            .remove(KEY_EMAIL)
            .remove(KEY_AUTH_ID)
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .apply()
        _username = username
        _role = role
        _donorId = donorId
        _email = null
        _authUserId = null
        _accessToken = null
        _refreshToken = null
    }

    /**
     * Supabase Auth sign-in / registration: saves the local account
     * fields AND the Auth session in one step. [username] is the email
     * for Auth accounts (local rows are keyed by email from v1.4 on).
     */
    fun saveAuth(
        context: Context,
        username: String,
        role: String,
        donorId: Long?,
        email: String,
        authUserId: String?,
        accessToken: String,
        refreshToken: String
    ) {
        _appContext = context.applicationContext
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_USER, username)
            .putString(KEY_ROLE, role)
            .putLong(KEY_DONOR_ID, donorId ?: -1L)
            .putString(KEY_EMAIL, email)
            .putString(KEY_AUTH_ID, authUserId)
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .apply()
        _username = username
        _role = role
        _donorId = donorId
        _email = email
        _authUserId = authUserId
        _accessToken = accessToken
        _refreshToken = refreshToken
    }

    /**
     * Replaces the stored tokens after a successful refresh (see
     * [SupabaseClient]). Safe to call from a background thread:
     * SharedPreferences.apply() updates the in-process copy at once.
     * If [load] was never called, only the in-memory copy is updated.
     */
    fun updateTokens(accessToken: String, refreshToken: String) {
        _accessToken = accessToken
        _refreshToken = refreshToken
        _appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_ACCESS, accessToken)
            ?.putString(KEY_REFRESH, refreshToken)
            ?.apply()
    }

    /** Drops the Auth tokens (dead session) but keeps the local account. */
    fun clearTokens() {
        _accessToken = null
        _refreshToken = null
        _appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove(KEY_ACCESS)
            ?.remove(KEY_REFRESH)
            ?.apply()
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _username = null
        _role = null
        _donorId = null
        _email = null
        _authUserId = null
        _accessToken = null
        _refreshToken = null
        _profilePendingSync = false
    }

    val isLoggedIn: Boolean get() = _username != null
    val isManager: Boolean get() = _role == ROLE_MANAGER

    // Backed by the last Context that called load() / save() / saveAuth();
    // activities call load() first. _appContext is the application
    // context only (never an Activity), so holding it is leak-safe, and
    // it lets background token refreshes persist without a Context.
    private var _appContext: Context? = null
    private var _username: String? = null
    private var _role: String? = null
    private var _donorId: Long? = null
    private var _email: String? = null
    private var _authUserId: String? = null
    private var _accessToken: String? = null
    private var _refreshToken: String? = null
    private var _profilePendingSync: Boolean = false

    fun load(context: Context) {
        _appContext = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _username = p.getString(KEY_USER, null)
        _role = p.getString(KEY_ROLE, null)
        val id = p.getLong(KEY_DONOR_ID, -1L)
        _donorId = if (id >= 0) id else null
        _email = p.getString(KEY_EMAIL, null)
        _authUserId = p.getString(KEY_AUTH_ID, null)
        _accessToken = p.getString(KEY_ACCESS, null)
        _refreshToken = p.getString(KEY_REFRESH, null)
        _profilePendingSync = p.getBoolean(KEY_PROFILE_PENDING, false)
    }

    val username: String? get() = _username
    val role: String? get() = _role
    val donorId: Long? get() = _donorId
    val email: String? get() = _email
    val authUserId: String? get() = _authUserId
    val accessToken: String? get() = _accessToken
    val refreshToken: String? get() = _refreshToken
    val profilePendingSync: Boolean get() = _profilePendingSync

    /**
     * True when a donor registered through Supabase Auth but their
     * donor profile never reached Supabase (the insert failed after a
     * successful sign-up). ProfileActivity shows a "Sync profile"
     * button while this is true, and clears it once the sync lands.
     */
    fun setProfilePendingSync(context: Context, pending: Boolean) {
        _appContext = context.applicationContext
        _profilePendingSync = pending
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PROFILE_PENDING, pending)
            .apply()
    }

    fun label(): String = when (_role) {
        ROLE_MANAGER -> "$_username • Manager"
        ROLE_DONOR -> "$_username • Donor"
        else -> _username ?: "Guest"
    }
}
