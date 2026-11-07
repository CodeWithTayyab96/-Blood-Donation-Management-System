package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class ShareTextTest {

    private fun full(city: String? = "Faisalabad", contact: String? = "+923001112233") =
        ShareText.requestText(
            heading = "Blood needed",
            group = "Blood group: O-",
            units = "Units needed: 2",
            hospital = "Hospital: Allied Hospital",
            city = city,
            contact = contact,
            footer = "Shared from Blood Donation"
        )

    @Test
    fun `all lines appear in a stable order`() {
        assertEquals(
            "Blood needed\nBlood group: O-\nUnits needed: 2\nHospital: Allied Hospital\n" +
                "Faisalabad\n+923001112233\nShared from Blood Donation",
            full(city = "Faisalabad", contact = "+923001112233")
        )
    }

    @Test
    fun `a missing city and a hidden contact are omitted not shown as blank`() {
        val text = full(city = null, contact = null)
        assertEquals(
            "Blood needed\nBlood group: O-\nUnits needed: 2\nHospital: Allied Hospital\n" +
                "Shared from Blood Donation",
            text
        )
        assertFalse(text.contains("City"))
        assertFalse(text.contains("Contact"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `blank and whitespace only optional lines are omitted too`() {
        assertEquals(full(null, null), full("", "   "))
    }

    @Test
    fun `surrounding whitespace on a line is trimmed`() {
        val text = full(city = "  Lahore  ", contact = "  0300-1112233 ")
        assertTrue(text.contains("Lahore"))
        assertFalse(text.contains("  Lahore  "))
    }

    @Test
    fun `no trailing newline is produced`() {
        assertFalse(full().endsWith("\n"))
    }
}
