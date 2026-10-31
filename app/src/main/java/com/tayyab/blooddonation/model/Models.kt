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

/** P2P: a report filed against a request (spam, fake, abuse). */
data class Report(
    val id: Long = 0,
    val requestId: Long,
    val reporterId: String?,
    val reason: String,
    val createdAt: String = "",
    val resolvedAt: String? = null
)
