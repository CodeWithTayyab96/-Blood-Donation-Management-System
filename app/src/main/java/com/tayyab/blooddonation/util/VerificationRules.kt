package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Verification

/**
 * Phase 3: donor verification rules.
 *
 * A donor may have several verification records over time (submitted, approved,
 * rejected, re-submitted). "Verified" means the most recent record by id is the
 * approved one — a later rejection or pending re-submission clears it. Pure so the
 * status derivation is unit tested.
 */
object VerificationRules {

    const val PENDING = "pending"
    const val VERIFIED = "verified"
    const val REJECTED = "rejected"

    /** True only when the latest record (highest id) is [VERIFIED]. */
    fun isVerified(records: List<Verification>): Boolean =
        records.maxByOrNull { it.id }?.status == VERIFIED

    /** Only a manager may change a donor's verification. */
    fun canVerify(roleIsManager: Boolean): Boolean = roleIsManager
}
