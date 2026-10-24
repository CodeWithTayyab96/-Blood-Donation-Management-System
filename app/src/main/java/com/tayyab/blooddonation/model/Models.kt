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
    val lastDonationDate: String? = null   // ISO yyyy-MM-dd, null = never donated
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
    val status: String = "Pending",  // Pending / Fulfilled
    val createdAt: String
)
