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

    fun all(prefs: SharedPreferences): List<PendingRequest> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { decode(it) }

    /** Adds [p]; returns false (and stores nothing) when it duplicates a queued one. */
    fun add(prefs: SharedPreferences, p: PendingRequest): Boolean {
        val current = all(prefs).toMutableList()
        if (EmergencyLogic.isDuplicate(current, p)) return false
        current.add(p)
        prefs.edit().putStringSet(KEY, current.map { encode(it) }.toSet()).apply()
        return true
    }

    fun clear(prefs: SharedPreferences) = prefs.edit().remove(KEY).apply()

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
