package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class CertificateTextTest {

    private fun lines(name: String = "Ali Raza", group: String = "O-", date: String = "2026-11-07") =
        CertificateText.lines(
            title = "Certificate of Donation",
            nameLabel = "Donor", name = name,
            groupLabel = "Blood group", group = group,
            dateLabel = "Date", date = date,
            thanks = "Thank you for giving the gift of life."
        )

    @Test
    fun `the certificate has a title facts and a thank you in order`() {
        assertEquals(
            listOf(
                "Certificate of Donation",
                "Donor: Ali Raza",
                "Blood group: O-",
                "Date: 2026-11-07",
                "Thank you for giving the gift of life."
            ),
            lines()
        )
    }

    @Test
    fun `a blank value does not render an empty label`() {
        val result = lines(name = "")
        assertEquals(4, result.size)
        assertFalse(result.any { it.startsWith("Donor") })
    }

    @Test
    fun `whitespace is trimmed from every line`() {
        assertTrue(lines(name = "  Ali Raza  ").contains("Donor: Ali Raza"))
    }
}
