package com.tayyab.blooddonation.util

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.tayyab.blooddonation.BuildConfig

/**
 * Push notifications (Phase 3): Firebase Cloud Messaging, wired up WITHOUT
 * the google-services Gradle plugin or a committed google-services.json.
 *
 * The four Firebase identifiers arrive as BuildConfig fields (Gradle project
 * properties / GitHub secrets of the same names — see PUSH_SETUP.md). With
 * any of them missing this app reports [isConfigured] = false and does
 * nothing: no Firebase classes are initialised, and alerting keeps working
 * the old way, through the WorkManager polling fallback in
 * [RequestAlertScheduler]. That is also what every CI build without Firebase
 * secrets does.
 *
 * Token lifecycle:
 *  - at sign-in (MainActivity, once per session) and whenever Firebase
 *    rotates the token ([BloodMessagingService.onNewToken]), the current
 *    token is uploaded to Supabase `device_tokens` for the signed-in user —
 *    an upsert by token, so one device adds/refreshes exactly one row;
 *  - a record of (user id, token) is kept locally so re-uploading only ever
 *    happens when one of them changed;
 *  - on sign-out the row for THIS device is deleted, so a shared phone stops
 *    announcing its old owner's new offers. Tokens for the user's other
 *    devices are deliberately left alone.
 *
 * THREADING: every blocking call here (token fetch, network) must run on a
 * background thread — callers use [Background]. Nothing here touches the UI.
 */
object FcmPush {

    private const val PREFS = "fcm_push"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_UPLOADED_FOR_USER = "fcm_uploaded_user"

    /** True only when all four Firebase values were baked into this build. */
    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    /** What this device's token is currently stored under, if uploaded. */
    data class StoredToken(val userId: String, val token: String)

    /**
     * Initialises Firebase explicitly from the BuildConfig values. Safe and
     * cheap to call repeatedly; returns true only when a usable app exists.
     *
     * Uses the pre-24.x Java API surface (firebase-common 20.4.2, which
     * firebase-messaging 23.4.1 pins): [FirebaseApp.getApps] needs a Context
     * here, and the sender id goes through setGcmSenderId (the setSenderId
     * alias was removed in 20.1.0). Written from a JVM-less environment
     * against published source of that version — see PUSH_SETUP.md.
     */
    fun initIfNeeded(context: Context): Boolean {
        if (!isConfigured) return false
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).isNotEmpty()) return true
        return try {
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(app, options)
            FirebaseApp.getApps(app).isNotEmpty()
        } catch (_: Exception) {
            // Wrong or partial credentials: stay silent and keep polling.
            false
        }
    }

    private fun store(context: Context, userId: String, token: String) {        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_UPLOADED_FOR_USER, userId)
            .apply()
    }

    fun stored(context: Context): StoredToken? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        val user = p.getString(KEY_UPLOADED_FOR_USER, null)?.takeIf { it.isNotBlank() }
        if (token == null || user == null) {
            // One without the other is an inconsistent record; drop both.
            if (token != null || user != null) {
                p.edit().remove(KEY_TOKEN).remove(KEY_UPLOADED_FOR_USER).apply()
            }
            return null
        }
        return StoredToken(user, token)
    }

    /**
     * Fetches the FCM token for this device and makes sure the right row
     * exists in Supabase. Blocks on network I/O; run on a background thread.
     *
     * Returns true when the server has (or just got) the correct row, false
     * when nothing could be done — Firebase not configured, not signed in
     * through Auth (a local-only manager session cannot be reached by user
     * id), or the upload failed. Callers may just retry later; nothing here
     * prompts or warns the user.
     */
    fun refreshToken(context: Context): Boolean {
        if (!Session.isLoggedIn) return false
        val userId = Session.authUserId ?: return false
        if (!initIfNeeded(context)) return false
        val token = try {
            // getToken() (not the Kotlin "token" property): the plain method
            // is the only form that compiles against this SDK version.
            Tasks.await(FirebaseMessaging.getInstance().getToken())
        } catch (_: Exception) {
            return false
        }
        if (token.isBlank()) return false
        val already = stored(context)
        if (already != null && already.token == token && already.userId == userId) {
            return true
        }
        store(context, userId, token)
        // Upsert by token: first upload inserts, a later upload by any of the
        // user's devices on the same phone refreshes the same row (updated_at
        // keeps it inside the server's 45-day fresh filter).
        return SupabaseClient.upsertDeviceToken(userId, token)
    }

    /**
     * Removes THIS device's token row on Supabase (used on sign-out). Blocks
     * on network I/O; call from [Background]. The local record is cleared
     * even when the network call fails — an orphan server row only means one
     * push to an empty prefs entry, harmless. If no token was ever uploaded,
     * this is a no-op that returns true.
     */
    fun forgetToken(context: Context): Boolean {
        val already = stored(context) ?: return true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return SupabaseClient.deleteDeviceToken(already.userId, already.token)
    }
}
