package com.tayyab.blooddonation.util

import android.content.Context
import java.security.MessageDigest

/**
 * The signed-in account for this session, kept in SharedPreferences.
 * This is a local, offline demo login (the same idea as the user/manager
 * logins in other blood-donation apps): it gates what the user can edit,
 * it is NOT a server authentication system, and passwords are stored only
 * as a SHA-256 hash salted with the username.
 */
object Session {

    private const val PREFS = "blood_session"
    private const val KEY_USER = "username"
    private const val KEY_ROLE = "role"
    private const val KEY_DONOR_ID = "donor_id"

    const val ROLE_MANAGER = "MANAGER"
    const val ROLE_DONOR = "DONOR"

    fun hashPassword(username: String, password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$username:$password".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun save(context: Context, username: String, role: String, donorId: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_USER, username)
            .putString(KEY_ROLE, role)
            .putLong(KEY_DONOR_ID, donorId ?: -1L)
            .apply()
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    val isLoggedIn: Boolean get() = _username != null
    val isManager: Boolean get() = _role == ROLE_MANAGER

    // Backed by the last Context that called load(); activities call load() first.
    private var _username: String? = null
    private var _role: String? = null
    private var _donorId: Long? = null

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _username = p.getString(KEY_USER, null)
        _role = p.getString(KEY_ROLE, null)
        val id = p.getLong(KEY_DONOR_ID, -1L)
        _donorId = if (id >= 0) id else null
    }

    val username: String? get() = _username
    val role: String? get() = _role
    val donorId: Long? get() = _donorId

    fun label(): String = when (_role) {
        ROLE_MANAGER -> "$_username • Manager"
        ROLE_DONOR -> "$_username • Donor"
        else -> _username ?: "Guest"
    }
}
