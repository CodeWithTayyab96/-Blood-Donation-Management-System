package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.BloodRequest

/**
 * Phase 3: offline emergency mode.
 *
 * When the network is unavailable the app still opens to a cached list of the most
 * urgent recent requests, and any new request the user composes is queued locally
 * and flushed on reconnect. Pure so the queue / de-dup rules are unit tested
 * without a device or a network.
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
