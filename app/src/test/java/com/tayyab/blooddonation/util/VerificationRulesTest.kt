package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Verification
import org.junit.Assert.*
import org.junit.Test

class VerificationRulesTest {

    @Test
    fun `no records means not verified`() {
        assertFalse(VerificationRules.isVerified(emptyList()))
    }

    @Test
    fun `latest verified record means verified`() {
        val records = listOf(
            Verification(1, 10, VerificationRules.PENDING),
            Verification(2, 10, VerificationRules.VERIFIED)
        )
        assertTrue(VerificationRules.isVerified(records))
    }

    @Test
    fun `latest rejected record means not verified`() {
        val records = listOf(
            Verification(1, 10, VerificationRules.VERIFIED),
            Verification(2, 10, VerificationRules.REJECTED)
        )
        assertFalse(VerificationRules.isVerified(records))
    }

    @Test
    fun `latest pending resubmission means not verified`() {
        val records = listOf(
            Verification(1, 10, VerificationRules.VERIFIED),
            Verification(2, 10, VerificationRules.PENDING)
        )
        assertFalse(VerificationRules.isVerified(records))
    }

    @Test
    fun `only a manager may verify`() {
        assertTrue(VerificationRules.canVerify(roleIsManager = true))
        assertFalse(VerificationRules.canVerify(roleIsManager = false))
    }
}
