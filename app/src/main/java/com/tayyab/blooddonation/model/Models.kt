package com.tayyab.blooddonation.model

data class Donor(
    val id: Long = 0,
    val name: String,
    val age: Int,
    val gender: String,
    val bloodGroup: String,
    val phone: String,
    val city: String,
    val weightKg: Double,
    val lastDonationDate: String? = null,   // ISO yyyy-MM-dd, null = never donated
    // P2P: links the donor profile to its Supabase Auth user (null for
    // manager-seeded demo donors, who have no Auth account of their own).
    val authId: String? = null,
    // P2P: whether the donor has switched "I'm available to donate" on.
    val availableToDonate: Boolean = false
)

data class Donation(
    val id: Long = 0,
    val donorId: Long,
    val donorName: String,
    val bloodGroup: String,
    val date: String,       // ISO yyyy-MM-dd
    val units: Int
)

/** Local app account. Roles: MANAGER (full access) or DONOR (own profile only
 *  for edits). Passwords are never stored in plain text — see Session.hashPassword. */
data class UserAccount(
    val username: String,
    val passwordHash: String,
    val role: String,           // MANAGER / DONOR
    val donorId: Long? = null   // the donor profile this account belongs to (donors only)
)

data class BloodRequest(
    val id: Long = 0,
    val patientName: String,
    val hospital: String,
    val bloodGroup: String,
    val unitsNeeded: Int,
    val urgency: String,    // Normal / Urgent / Critical
    val contact: String,
    // Pending / Matched / Fulfilled / Cancelled / Expired. Matched means an
    // offer was accepted; Fulfilled means both sides confirmed the donation.
    val status: String = "Pending",
    val createdAt: String,
    // P2P: Supabase Auth id of the creator. Null on legacy / manager rows,
    // which are treated as manager-owned.
    val requesterId: String? = null,
    // P2P: where the blood is needed; drives "nearby first" browsing.
    val city: String = "",
    // P2P: "stock" (manager fulfilled from inventory) or "donor" (a peer
    // donation completed), or null until fulfilled.
    val fulfilledBy: String? = null,
    // P2P: optional ISO date the request is needed by; drives lazy Expired.
    val neededBy: String? = null
)

/**
 * P2P: a donor's offer to fulfil a request. Denormalises the donor's display
 * fields at offer time so the requester sees "first name, group, city" without
 * ever reading the donor's phone (that stays in [ContactDetail] and is only
 * revealed after the requester accepts the offer).
 */
data class Offer(
    val id: Long = 0,
    val requestId: Long,
    val donorId: Long,                 // local donor id
    val donorAuthId: String? = null,   // Supabase Auth id of the donor
    val donorName: String = "",
    val donorGroup: String = "",
    val donorCity: String = "",
    // Offered / Accepted / Declined / Completed.
    val status: String = "Offered",
    val donorConfirmedAt: String? = null,
    val requesterConfirmedAt: String? = null,
    val createdAt: String = ""
)

/** P2P: a donor's private phone, keyed by their Auth user id. */
data class ContactDetail(
    val userId: String,
    val phone: String
)

/**
 * Phase 2: one step in a request's status history.
 *
 * [actor] is a display label ("Requester", "Manager", "Donor"), never an Auth
 * id or a phone number — the timeline is readable by the requester and the
 * manager, and must not leak who else is signed up.
 */
data class StatusEvent(
    val id: Long = 0,
    val requestId: Long,
    val status: String,          // Pending / Matched / Fulfilled / Cancelled / Expired
    val changedAt: String,       // ISO date or full timestamptz
    val actor: String? = null
)

/** P2P: a report filed against a request (spam, fake, abuse). */
data class Report(
    val id: Long = 0,
    val requestId: Long,
    val reporterId: String?,
    val reason: String,
    val createdAt: String = "",
    val resolvedAt: String? = null
)

// ---------------------------------------------------------------------------
// Phase 3 models
// ---------------------------------------------------------------------------

/**
 * A dated change to stock for one blood group. Inflow (donations) is positive;
 * outflow (fulfilments, wastage) is negative. The server is the source of truth
 * and the app records local movements then syncs them, so real net burn can be
 * forecast instead of guessed from requests.
 */
data class Movement(
    val id: Long = 0,
    val bloodGroup: String,
    val delta: Int,        // +inflow / -outflow
    val reason: String,    // "donation" | "fulfilment" | "wastage" | "adjustment"
    val date: String       // ISO yyyy-MM-dd
)

/** A blood donation camp. */
data class Camp(
    val id: Long = 0,
    val name: String,
    val location: String,
    val city: String,
    val campDate: String,  // ISO yyyy-MM-dd
    val organizer: String = ""
)

/** A donor's check-in at a camp (the attendance record). */
data class CampAttendance(
    val id: Long = 0,
    val campId: Long,
    val donorId: Long,
    val attendedAt: String // ISO date or timestamptz
)

/** A bookable appointment slot with a capacity guard enforced at the DB level. */
data class AppointmentSlot(
    val id: Long = 0,
    val slotTime: String,  // ISO datetime
    val capacity: Int,
    val booked: Int = 0,
    val note: String = ""
)

/** One donor's booking on a slot. */
data class AppointmentBooking(
    val id: Long = 0,
    val slotId: Long,
    val donorId: Long,
    val createdAt: String = ""
)

/** A donor verification record, set by a manager. */
data class Verification(
    val id: Long = 0,
    val donorId: Long,
    val status: String,    // "pending" | "verified" | "rejected"
    val verifiedBy: String? = null,
    val verifiedAt: String? = null,
    val note: String = ""
)
