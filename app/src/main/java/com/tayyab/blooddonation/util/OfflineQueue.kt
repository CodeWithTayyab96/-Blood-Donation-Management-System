package com.tayyab.blooddonation.util

import android.content.SharedPreferences

/**
 * Phase 3: the offline request queue. A request composed with no network is held
 * here until the app is back online, then flushed. Stored in SharedPreferences as a
 * small pipe-delimited set, so a queued request survives an app restart.
 *
 * De-duplication is delegated to [EmergencyLogic.isDuplicate] (unit tested); this
 * class only persists. Fields are sanitised on write so the delimiter never leaks
 * into a field and breaks parsing on read.
 */
object OfflineQueue {

    private const val KEY = "offline_request_queue"
    private const val SEP = "|"

    private const val KEY_LIST = "offline_request_queue_list"

    fun all(prefs: SharedPreferences): List<PendingRequest> {
        val json = prefs.getString(KEY_LIST, null)
        if (json != null) {
            return json.split("\n").filter { it.isNotBlank() }.mapNotNull { decode(it) }
        }
        return prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { decode(it) }
    }

    /** Adds [p]; returns false (and stores nothing) when it duplicates a queued one. */
    fun add(prefs: SharedPreferences, p: PendingRequest): Boolean {
        val current = all(prefs).toMutableList()
        if (EmergencyLogic.isDuplicate(current, p)) return false
        current.add(p)
        save(prefs, current)
        return true
    }

    /** Drops one queued request, after it was sent. A request that fails to send stays queued. */
    fun remove(prefs: SharedPreferences, p: PendingRequest) {
        val kept = all(prefs).filter { it != p }
        save(prefs, kept)
    }

    fun clear(prefs: SharedPreferences) = prefs.edit().remove(KEY).remove(KEY_LIST).apply()

    private fun save(prefs: SharedPreferences, items: List<PendingRequest>) {
        prefs.edit()
            .remove(KEY)
            .putString(KEY_LIST, items.joinToString("\n") { encode(it) })
            .apply()
    }

    private fun encode(p: PendingRequest): String =
        listOf(
            p.bloodGroup, p.units.toString(), p.city, p.hospital, p.urgency, p.queuedAt
        ).joinToString(SEP) { it.replace(SEP, " ") }

    private fun decode(s: String): PendingRequest? {
        val parts = s.split(SEP)
        if (parts.size != 6) return null
        return PendingRequest(
            bloodGroup = parts[0],
            units = parts[1].toIntOrNull() ?: 0,
            city = parts[2],
            hospital = parts[3],
            urgency = parts[4],
            queuedAt = parts[5]
        )
    }
}
