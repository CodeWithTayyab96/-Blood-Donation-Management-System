package com.tayyab.blooddonation.util

import android.content.Context
import com.tayyab.blooddonation.db.DbHelper
import java.time.LocalDate

/**
 * Phase 3: sends the emergency requests queued while offline.
 *
 * The queue used to be a dead end: [OfflineQueue] could hold requests, but
 * nothing ever sent them, so a request composed on the Emergency screen
 * never reached another donor even after the phone was back online. This is
 * the missing half. It runs from the Emergency screen when the server
 * answers, and from the background alert worker, so a queued request is
 * sent even if the user never reopens that screen.
 *
 * Does network I/O: callers run it off the UI thread. Assumes [Session] is
 * already loaded and the network is up; callers check that first.
 */
object EmergencyFlush {

    data class Result(val sent: Int, val blocked: Int, val failed: Int)

    /**
     * Sends every queued emergency request, in the order it was queued.
     *
     * A request is sent only when it can be owned: without a signed-in Auth
     * user there is no requester_id to put on the row, so it stays queued.
     * The 3-open-requests cap is respected too — a request over the cap stays
     * queued instead of being refused by the server on every run. A request
     * the server refuses for any other reason also stays queued, so a later
     * run can retry it. Only a request the server accepted is removed from
     * the queue and stored locally under its server id.
     */
    fun flush(context: Context): Result {
        val prefs = context.getSharedPreferences("blood_donation", Context.MODE_PRIVATE)
        val queued = OfflineQueue.all(prefs)
        if (queued.isEmpty()) return Result(0, 0, 0)
        val db = DbHelper(context)
        val requesterId = Session.authUserId
        if (requesterId.isNullOrBlank()) return Result(0, queued.size, 0)
        val contact = Session.donorId?.let { db.getDonor(it)?.phone }.orEmpty()
        val today = LocalDate.now().toString()
        var sent = 0
        var blocked = 0
        var failed = 0
        for (p in queued) {
            if (db.openRequestCountForRequester(requesterId) >= P2pRules.MAX_OPEN_REQUESTS) {
                blocked++
                continue
            }
            val req = EmergencyLogic.toRequest(p, requesterId, contact, today)
            val remoteId = SupabaseSync.addRequestRemote(req)
            if (remoteId == null) {
                failed++
                continue
            }
            db.insertRequestWithId(req.copy(id = remoteId))
            OfflineQueue.remove(prefs, p)
            sent++
        }
        return Result(sent, blocked, failed)
    }
}
