package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.BloodRequest

/**
 * What a push message can carry, decoded from an FCM data-only payload.
 *
 * Phase 3 sends data-only messages on purpose: the app (not the Firebase
 * system tray) builds the notification, so the same dedup rules and channels
 * apply whether the alert arrives by push or by the 15-minute poll fallback.
 *
 * Every field in an FCM data payload is a String, and anything missing or
 * malformed must be reported as "no payload" rather than thrown on — a bad
 * message must never crash the notification service.
 */
sealed interface PushData {
    /** An Urgent/Critical compatible request was created. Matches what the
     *  15-minute poll would have shown, so dedup works against it. */
    data class UrgentRequest(val request: BloodRequest) : PushData

    /** A peer offered to donate on [requestId]. */
    data class Offer(
        val requestId: Long,
        val offerId: Long,
        val title: String,
        val body: String
    ) : PushData

    /** A requester accepted [offerId]. */
    data class OfferAccepted(
        val requestId: Long,
        val offerId: Long,
        val title: String,
        val body: String
    ) : PushData
}

object PushParser {

    private const val TYPE_URGENT = "urgent_request"
    private const val TYPE_OFFER = "offer"
    private const val TYPE_ACCEPTED = "accepted"

    /**
     * Parses fields the server fills for THIS message type; unrelated fields
     * may be absent. Returns null for unknown types or unusable ids.
     */
    fun parse(data: Map<String, String>): PushData? {
        if (data.isEmpty()) return null
        return when (data["type"]) {
            TYPE_URGENT -> urgent(data)
            TYPE_OFFER -> offer(data, accepted = false)
            TYPE_ACCEPTED -> offer(data, accepted = true)
            else -> null
        }
    }

    private fun id(data: Map<String, String>, key: String): Long? =
        data[key]?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it > 0 }

    private fun text(data: Map<String, String>, key: String): String =
        data[key]?.trim().orEmpty()

    private fun urgent(data: Map<String, String>): PushData? {
        val requestId = id(data, "request_id") ?: return null
        val group = text(data, "blood_group")
        if (group.isEmpty()) return null
        val urgency = text(data, "urgency").ifEmpty { "Urgent" }
        val units = id(data, "units_needed")?.toInt() ?: 1
        return PushData.UrgentRequest(
            BloodRequest(
                id = requestId,
                patientName = "",   // deliberately not sent or shown
                hospital = text(data, "hospital"),
                bloodGroup = group,
                unitsNeeded = units,
                urgency = urgency,
                contact = text(data, "contact"),
                status = "Pending",
                createdAt = "",
                requesterId = null,
                city = text(data, "city"),
                fulfilledBy = null,
                neededBy = null
            )
        )
    }

    private fun offer(data: Map<String, String>, accepted: Boolean): PushData? {
        val requestId = id(data, "request_id") ?: return null
        val offerId = id(data, "offer_id") ?: return null
        val body = text(data, "body").ifEmpty {
            if (accepted) "Your donation offer was accepted"
            else "Someone offered to donate"
        }
        val title = text(data, "title").ifEmpty {
            if (accepted) "Offer accepted" else "New offer"
        }
        return if (accepted) PushData.OfferAccepted(requestId, offerId, title, body)
        else PushData.Offer(requestId, offerId, title, body)
    }
}
