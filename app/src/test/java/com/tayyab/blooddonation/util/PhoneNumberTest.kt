package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class PhoneNumberTest {

    @Test
    fun `a Pakistani mobile gains the country code and loses the trunk zero`() {
        assertEquals("+923001112233", PhoneNumber.normalize("03001112233"))
    }

    @Test
    fun `spaces and dashes in a stored number are ignored`() {
        assertEquals("+923001112233", PhoneNumber.normalize("0300-1112233"))
        assertEquals("+923001112233", PhoneNumber.normalize(" 0300 111 2233 "))
    }

    @Test
    fun `already-international Pakistani numbers stay unchanged`() {
        assertEquals("+923001112233", PhoneNumber.normalize("+92 300 1112233"))
        assertEquals("+923001112233", PhoneNumber.normalize("923001112233"))
        assertEquals("+923001112233", PhoneNumber.normalize("00923001112233"))
    }

    @Test
    fun `a ten digit mobile without the trunk zero is accepted`() {
        assertEquals("+923001112233", PhoneNumber.normalize("3001112233"))
    }

    @Test
    fun `a number with an explicit plus but another country is carried through`() {
        assertEquals("+15551234567", PhoneNumber.normalize("+1 (555) 123-4567"))
    }

    @Test
    fun `blank short and landline numbers are rejected so buttons stay hidden`() {
        assertNull(PhoneNumber.normalize(null))
        assertNull(PhoneNumber.normalize(""))
        assertNull(PhoneNumber.normalize("   "))
        assertNull(PhoneNumber.normalize("no number given"))
        assertNull(PhoneNumber.normalize("12345"))
        // A Pakistani landline is not a WhatsApp number and has no mobile prefix.
        assertNull(PhoneNumber.normalize("041-9200123"))
    }

    @Test
    fun `a too long number is rejected`() {
        assertNull(PhoneNumber.normalize("0300111223344556677"))
    }

    @Test
    fun `non ASCII digits are not silently turned into a broken link`() {
        assertNull(PhoneNumber.normalize("۰۳۰۰۱۱۱۲۲۳۳"))
    }

    @Test
    fun `isValid agrees with normalize`() {
        assertTrue(PhoneNumber.isValid("0300-1112233"))
        assertFalse(PhoneNumber.isValid("041-9200123"))
        assertFalse(PhoneNumber.isValid(null))
    }

    @Test
    fun `the dial uri and the wa me link use the same normalised number`() {
        val number = PhoneNumber.normalize("0300-1112233")!!
        assertEquals("tel:+923001112233", PhoneNumber.dialUri(number))
        assertEquals("https://wa.me/923001112233", PhoneNumber.waMeUrl(number))
    }
}
