package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Donor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object BloodCompatibility {

    val ALL_GROUPS = listOf("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+")

    /** Which recipient groups each donor group can give blood to. */
    private val canDonateTo: Map<String, Set<String>> = mapOf(
        "O-"  to setOf("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+"), // universal donor
        "O+"  to setOf("O+", "A+", "B+", "AB+"),
        "A-"  to setOf("A-", "A+", "AB-", "AB+"),
        "A+"  to setOf("A+", "AB+"),
        "B-"  to setOf("B-", "B+", "AB-", "AB+"),
        "B+"  to setOf("B+", "AB+"),
        "AB-" to setOf("AB-", "AB+"),
        "AB+" to setOf("AB+") // can only give to AB+, but can receive from everyone
    )

    /** Donor groups whose blood a patient of [patientGroup] can safely receive. */
    fun donorGroupsFor(patientGroup: String): List<String> =
        ALL_GROUPS.filter { g -> canDonateTo[g]?.contains(patientGroup) == true }

    fun isCompatible(donorGroup: String, patientGroup: String): Boolean =
        canDonateTo[donorGroup]?.contains(patientGroup) == true

    const val MIN_AGE = 18
    const val MAX_AGE = 65
    const val MIN_WEIGHT_KG = 50.0
    const val MIN_GAP_DAYS = 90L   // minimum days between two whole-blood donations

    /** Human-readable eligibility check used on the donor detail screen. */
    fun eligibility(donor: Donor, today: LocalDate = LocalDate.now()): Pair<Boolean, String> {
        if (donor.age < MIN_AGE) return false to "Under 18 years of age"
        if (donor.age > MAX_AGE) return false to "Above 65 years of age"
        if (donor.weightKg < MIN_WEIGHT_KG) return false to "Weight below 50 kg"
        val last = donor.lastDonationDate
        if (!last.isNullOrBlank()) {
            val days = ChronoUnit.DAYS.between(LocalDate.parse(last), today)
            if (days < MIN_GAP_DAYS) {
                val wait = MIN_GAP_DAYS - days
                return false to "Donated $days days ago — eligible again in $wait days"
            }
        }
        return true to "Eligible to donate"
    }
}
