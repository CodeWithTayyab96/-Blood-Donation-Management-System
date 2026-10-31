package com.tayyab.blooddonation.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

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
 * Storage: everything lives in an [EncryptedSharedPreferences] file
 * ("blood_session_secure") keyed by the Android Keystore. Earlier releases
 * used a plain file called "blood_session"; [migrateLegacyPrefs] copies any
 * values out of it once and then deletes it, so upgrading does not sign
 * anyone out. If the Keystore is unusable (a corrupted entry, or a restore
 * from a backup taken before encryption existed) the store falls back to a
 * plain file rather than crashing the app.
 *
 * Passwords: the local hash is PBKDF2WithHmacSHA256, 200,000 iterations,
 * 256-bit, with a random 16-byte salt per hash. The stored string is
 * `pbkdf2$<iterations>$<salt hex>$<hash hex>`, so the work factor travels
 * with the hash and can be raised later. Hashes written by older releases
 * (single-round SHA-256 salted with the username) no longer verify — see
 * [PasswordCheck.LEGACY_FORMAT] and [needsRehash].
 *
 * [logout] clears the local state and revokes the Supabase Auth session in
 * the background with the token captured beforehand, so the network call
 * can neither block the UI nor prevent the local logout.
 */
object Session {

    /** Encrypted store. */
    private const val PREFS = "blood_session_secure"

    /**
     * Used only when the Keystore is unusable. Deliberately a DIFFERENT file:
     * writing plain values into [PREFS] would make it unopenable as an
     * encrypted store for ever after.
     */
    private const val PREFS_PLAIN = "blood_session_plain"

    /** The plain-text file used up to v1.4; migrated once, then deleted. */
    private const val LEGACY_PREFS = "blood_session"

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

    // ------------------------------------------------------------- passwords
    private const val HASH_SCHEME = "pbkdf2"
    private const val HASH_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    /** Work factor written into every new hash. Raise it and [needsRehash] follows. */
    const val PBKDF2_ITERATIONS = 200_000

    /** The outcome of checking a typed password against a stored hash. */
    enum class PasswordCheck {
        /** Password matches. */
        OK,

        /** Password does not match. */
        WRONG,

        /**
         * The stored hash predates PBKDF2 (a single-round SHA-256 from an
         * older release), so it cannot be checked any more. The account still
         * works — it just has to sign in through Supabase Auth once, which
         * rewrites the hash in the current format. Callers must say so rather
         * than reporting "wrong password".
         */
        LEGACY_FORMAT
    }

    private const val HEX_CHARS = "0123456789abcdef"

    /**
     * Lower-case hex, two characters per byte.
     *
     * Written out by hand on purpose: `"%02x".format(byte)` does not reliably
     * produce two characters for a negative byte (Java's Formatter widens the
     * Byte to a Long first, so -1 can come out as "ffffffffffffffff"). Since
     * [fromHex] splits this back into fixed two-character pairs, that would
     * misalign the salt and make every hash unverifiable.
     */
    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
        }
        return out.toString()
    }

    private fun fromHex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return try {
            ByteArray(text.length / 2) { i ->
                text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).encoded
    }

    /**
     * Hashes a password for the local account row. The salt is random per
     * hash, so the same password produces a different string every time —
     * always compare with [checkPassword], never with `==`.
     */
    fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password, salt, PBKDF2_ITERATIONS)
        return "$HASH_SCHEME\$$PBKDF2_ITERATIONS\$${hex(salt)}\$${hex(hash)}"
    }

    /**
     * True when [stored] is missing, is in the old SHA-256 format, or was
     * produced with a lower iteration count than [PBKDF2_ITERATIONS]. Such a
     * hash should be rewritten the next time the password is known — which,
     * for a legacy hash, only happens at an online Supabase Auth sign-in.
     */
    fun needsRehash(stored: String?): Boolean {
        if (stored == null) return true
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != HASH_SCHEME) return true
        val iterations = parts[1].toIntOrNull() ?: return true
        return iterations < PBKDF2_ITERATIONS
    }

    /** Checks a typed password against a stored hash. Never throws. */
    fun checkPassword(stored: String?, password: String): PasswordCheck {
        if (stored.isNullOrBlank()) return PasswordCheck.WRONG
        if (needsRehash(stored)) return PasswordCheck.LEGACY_FORMAT
        val parts = stored.split("$")
        val salt = fromHex(parts[2]) ?: return PasswordCheck.LEGACY_FORMAT
        val expected = fromHex(parts[3]) ?: return PasswordCheck.LEGACY_FORMAT
        val iterations = parts[1].toIntOrNull() ?: PBKDF2_ITERATIONS
        val actual = pbkdf2(password, salt, iterations)
        // Constant-time compare, so the check cannot be timed.
        return if (MessageDigest.isEqual(expected, actual)) PasswordCheck.OK
        else PasswordCheck.WRONG
    }

    // ------------------------------------------------------------- storage
    private var _prefs: SharedPreferences? = null

    private fun store(context: Context): SharedPreferences {
        _appContext = context.applicationContext
        _prefs?.let { return it }
        val opened = openStore(context.applicationContext)
        _prefs = opened
        return opened
    }

    private fun openStore(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateLegacyPrefs(context, secure)
            secure
        } catch (e: Exception) {
            // Keystore unavailable or its entry corrupted (typically after a
            // restore from a backup taken before encryption existed). Losing
            // the session is bad; crashing on every launch is worse.
            val plain = context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            migrateLegacyPrefs(context, plain)
            plain
        }
    }

    /**
     * Copies the values out of the pre-encryption "blood_session" file into the
     * encrypted one, then deletes the plain-text file. Runs once: the legacy
     * file is empty afterwards, so the early return makes this a no-op.
     *
     * The write is committed before the plain file is dropped. If that commit
     * fails the plain file is still removed — leaving Auth tokens sitting in an
     * unencrypted file that nothing reads any more is the worse outcome, and
     * the only cost is that the user signs in again.
     */
    private fun migrateLegacyPrefs(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val editor = secure.edit()
        for ((key, value) in legacy.all) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.commit()
        legacy.edit().clear().commit()
    }

    // ------------------------------------------------------------- session state

    /**
     * Local-only sign-in (the seeded manager, or the offline fallback). A local
     * sign-in carries no Auth session, so any previously stored Auth tokens are
     * cleared with it — otherwise a later online call would go out under the
     * previous user's token.
     */
    fun save(context: Context, username: String, role: String, donorId: Long?) {
        store(context).edit()
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
        store(context).edit()
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
        val prefs = _prefs ?: _appContext?.let { store(it) } ?: return
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .apply()
    }

    /** Drops the Auth tokens (dead session) but keeps the local account. */
    fun clearTokens() {
        _accessToken = null
        _refreshToken = null
        val prefs = _prefs ?: _appContext?.let { store(it) } ?: return
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .apply()
    }

    /**
     * Signs out.
     *
     * The Auth token is captured first, then all local state is cleared
     * immediately, then the Supabase Auth session is revoked on a background
     * thread. The order matters: the revocation needs the token, and a network
     * failure (or no network at all) must never leave the user still signed in
     * on the device. If the revoke call fails the local logout has already
     * happened; the refresh token simply stays valid on the server until it
     * expires, which is why it is also cleared here.
     */
    fun logout(context: Context) {
        val access = _accessToken
        store(context).edit().clear().apply()
        _username = null
        _role = null
        _donorId = null
        _email = null
        _authUserId = null
        _accessToken = null
        _refreshToken = null
        _profilePendingSync = false
        if (!access.isNullOrBlank() && SupabaseAuth.isConfigured) {
            Background.run { SupabaseAuth.signOut(access) }
        }
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
        val p = store(context)
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
        _profilePendingSync = pending
        store(context).edit()
            .putBoolean(KEY_PROFILE_PENDING, pending)
            .apply()
    }

    fun label(): String = when (_role) {
        ROLE_MANAGER -> "$_username • Manager"
        ROLE_DONOR -> "$_username • Donor"
        else -> _username ?: "Guest"
    }
}
