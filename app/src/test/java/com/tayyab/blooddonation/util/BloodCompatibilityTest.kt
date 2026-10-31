package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Donor
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's only real domain logic, and until now it had no tests at all.
 *
 * Every test passes an explicit `today`, so nothing here depends on the date
 * the suite happens to run on.
 */
class BloodCompatibilityTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 31)

    private fun donor(
        age: Int = 30,
        weightKg: Double = 70.0,
        lastDonationDate: String? = null
    ) = Donor(
        name = "Test Donor",
        age = age,
        gender = "Male",
        bloodGroup = "O+",
        phone = "0000-0000000",
        city = "Testville",
        weightKg = weightKg,
        lastDonationDate = lastDonationDate
    )

    /**
     * The standard ABO/Rh transfusion table, written out independently of the
     * implementation so a bug in one is not mirrored in the other.
     */
    private val expectedRecipients: Map<String, Set<String>> = mapOf(
        "O-" to setOf("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+"),
        "O+" to setOf("O+", "A+", "B+", "AB+"),
        "A-" to setOf("A-", "A+", "AB-", "AB+"),
        "A+" to setOf("A+", "AB+"),
        "B-" to setOf("B-", "B+", "AB-", "AB+"),
        "B+" to setOf("B+", "AB+"),
        "AB-" to setOf("AB-", "AB+"),
        "AB+" to setOf("AB+")
    )

    // ------------------------------------------------------------ compatibility

    @Test
    fun `the compatibility table covers all 8 blood groups`() {
        assertEquals(8, BloodCompatibility.ALL_GROUPS.size)
        assertEquals(BloodCompatibility.ALL_GROUPS.toSet(), expectedRecipients.keys)
    }

    @Test
    fun `all 64 donor-recipient pairs match the transfusion table`() {
        for (donorGroup in BloodCompatibility.ALL_GROUPS) {
            val allowed = expectedRecipients.getValue(donorGroup)
            for (patientGroup in BloodCompatibility.ALL_GROUPS) {
                assertEquals(
                    "donor $donorGroup -> patient $patientGroup",
                    allowed.contains(patientGroup),
                    BloodCompatibility.isCompatible(donorGroup, patientGroup)
                )
            }
        }
    }

    @Test
    fun `O negative is the universal donor`() {
        for (patientGroup in BloodCompatibility.ALL_GROUPS) {
            assertTrue(
                "O- should be able to give to $patientGroup",
                BloodCompatibility.isCompatible("O-", patientGroup)
            )
        }
    }

    @Test
    fun `AB positive is the universal recipient`() {
        assertEquals(8, BloodCompatibility.donorGroupsFor("AB+").size)
    }

    @Test
    fun `AB positive donor can only give to AB positive`() {
        assertEquals(setOf("AB+"), expectedRecipients.getValue("AB+"))
        assertTrue(BloodCompatibility.isCompatible("AB+", "AB+"))
        for (patientGroup in BloodCompatibility.ALL_GROUPS - "AB+") {
            assertFalse(BloodCompatibility.isCompatible("AB+", patientGroup))
        }
    }

    @Test
    fun `O negative patient can only receive from O negative`() {
        assertEquals(listOf("O-"), BloodCompatibility.donorGroupsFor("O-"))
    }

    @Test
    fun `compatibility is directional`() {
        // A- gives to A+ but A+ cannot give to A-. The direction is the easiest
        // thing to get backwards.
        assertTrue(BloodCompatibility.isCompatible("A-", "A+"))
        assertFalse(BloodCompatibility.isCompatible("A+", "A-"))
    }

    @Test
    fun `donorGroupsFor agrees with isCompatible`() {
        for (patientGroup in BloodCompatibility.ALL_GROUPS) {
            val groups = BloodCompatibility.donorGroupsFor(patientGroup)
            for (donorGroup in BloodCompatibility.ALL_GROUPS) {
                assertEquals(
                    "donor $donorGroup -> patient $patientGroup",
                    groups.contains(donorGroup),
                    BloodCompatibility.isCompatible(donorGroup, patientGroup)
                )
            }
        }
    }

    @Test
    fun `unknown blood group is never compatible`() {
        assertFalse(BloodCompatibility.isCompatible("X+", "O+"))
        assertFalse(BloodCompatibility.isCompatible("O+", "X+"))
        assertTrue(BloodCompatibility.donorGroupsFor("X+").isEmpty())
    }

    // ------------------------------------------------------------ age and weight

    @Test
    fun `age 17 is not eligible`() {
        val (eligible, reason) = BloodCompatibility.eligibility(donor(age = 17), today)
        assertFalse(eligible)
        assertTrue(reason, reason.contains("18"))
    }

    @Test
    fun `age 18 is eligible`() {
        assertTrue(BloodCompatibility.eligibility(donor(age = 18), today).first)
    }

    @Test
    fun `age 65 is eligible`() {
        assertTrue(BloodCompatibility.eligibility(donor(age = 65), today).first)
    }

    @Test
    fun `age 66 is not eligible`() {
        val (eligible, reason) = BloodCompatibility.eligibility(donor(age = 66), today)
        assertFalse(eligible)
        assertTrue(reason, reason.contains("65"))
    }

    @Test
    fun `weight 49 point 9 kg is not eligible`() {
        val (eligible, reason) = BloodCompatibility.eligibility(donor(weightKg = 49.9), today)
        assertFalse(eligible)
        assertTrue(reason, reason.contains("50"))
    }

    @Test
    fun `weight of exactly 50 kg is eligible`() {
        assertTrue(BloodCompatibility.eligibility(donor(weightKg = 50.0), today).first)
    }

    // ------------------------------------------------------------ the 90-day rule

    @Test
    fun `never donated is eligible`() {
        assertTrue(BloodCompatibility.eligibility(donor(lastDonationDate = null), today).first)
    }

    @Test
    fun `donating today is not eligible`() {
        val (eligible, reason) =
            BloodCompatibility.eligibility(donor(lastDonationDate = today.toString()), today)
        assertFalse(eligible)
        assertTrue(reason, reason.contains("90"))
    }

    @Test
    fun `89 days ago is not eligible`() {
        val last = today.minusDays(89).toString()
        val (eligible, reason) = BloodCompatibility.eligibility(donor(lastDonationDate = last), today)
        assertFalse(eligible)
        assertTrue(reason, reason.contains("89"))
    }

    /** The exact boundary: 90 days is the first eligible day, 89 is not. */
    @Test
    fun `90 days ago is eligible`() {
        val last = today.minusDays(90).toString()
        assertTrue(BloodCompatibility.eligibility(donor(lastDonationDate = last), today).first)
    }

    @Test
    fun `91 days ago is eligible`() {
        val last = today.minusDays(91).toString()
        assertTrue(BloodCompatibility.eligibility(donor(lastDonationDate = last), today).first)
    }

    // ------------------------------------------------------------ next eligible date

    @Test
    fun `nextEligibleDate is null when the donor never donated`() {
        assertNull(BloodCompatibility.nextEligibleDate(donor(lastDonationDate = null), today))
    }

    @Test
    fun `nextEligibleDate is exactly 90 days after the last donation`() {
        val last = today.minusDays(89)
        assertEquals(
            today.plusDays(1),
            BloodCompatibility.nextEligibleDate(donor(lastDonationDate = last.toString()), today)
        )
    }

    @Test
    fun `nextEligibleDate is null on the 90th day itself`() {
        val last = today.minusDays(90).toString()
        assertNull(BloodCompatibility.nextEligibleDate(donor(lastDonationDate = last), today))
    }

    /**
     * The two functions must never disagree: a donor is eligible exactly when
     * there is no future eligible date.
     */
    @Test
    fun `nextEligibleDate is null exactly when the donor is eligible`() {
        for (offset in 0..100L) {
            val d = donor(lastDonationDate = today.minusDays(offset).toString())
            val eligible = BloodCompatibility.eligibility(d, today).first
            val next = BloodCompatibility.nextEligibleDate(d, today)
            assertEquals("offset $offset days", eligible, next == null)
        }
    }
}
