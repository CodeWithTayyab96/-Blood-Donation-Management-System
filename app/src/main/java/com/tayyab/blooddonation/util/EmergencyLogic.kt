package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.BloodRequest

/**
 * Phase 3: offline emergency mode.
 *
 * When the network is unavailable the app still opens to a cached list of the most
 * urgent recent requests, and any new request the user composes is queued locally
 * and flushed on reconnect (see [EmergencyFlush]). Pure so the queue / de-dup
 * rules are unit tested without a device or a network.
 */
object EmergencyLogic {

    /**
     * The requests to show in the offline view: the most urgent first, then most
     * recent, capped at [limit]. `createdAt` is an ISO string so a plain String
     * compare orders correctly within a time zone.
     */
    fun cachedForOffline(requests: List<BloodRequest>, limit: Int = 20): List<BloodRequest> =
        requests
            .sortedWith(
                compareByDescending<BloodRequest> { it.urgency == "Critical" }
                    .thenByDescending { it.urgency == "Urgent" }
                    .thenByDescending { it.createdAt }
            )
            .take(limit)

    /** True when [candidate] would duplicate an already-queued request. */
    fun isDuplicate(pending: List<PendingRequest>, candidate: PendingRequest): Boolean =
        pending.any {
            it.bloodGroup == candidate.bloodGroup &&
                it.city == candidate.city &&
                it.units == candidate.units &&
                it.hospital == candidate.hospital
        }

    /**
     * The real request a queued emergency request becomes when it is sent.
     *
     * The offline form collects no patient name, because in an emergency
     * there is no time for a full form — so the request is posted as an
     * emergency request from the signed-in user, with their phone as the
     * contact so donors can reach them. [today] is the day it is actually
     * posted, not the day it was queued.
     */
    fun toRequest(
        pending: PendingRequest,
        requesterId: String,
        contact: String,
        today: String
    ): BloodRequest =
        BloodRequest(
            patientName = "Emergency patient",
            hospital = pending.hospital,
            bloodGroup = pending.bloodGroup,
            unitsNeeded = pending.units,
            urgency = pending.urgency,
            contact = contact,
            status = "Pending",
            createdAt = today,
            requesterId = requesterId,
            city = pending.city
        )
}

/**
 * A request composed while offline, held until the network returns. Deliberately
 * a plain data class with no ids, so two identical queues never clash on a key.
 */
data class PendingRequest(
    val bloodGroup: String,
    val units: Int,
    val city: String,
    val hospital: String,
    val urgency: String,
    val queuedAt: String
)
