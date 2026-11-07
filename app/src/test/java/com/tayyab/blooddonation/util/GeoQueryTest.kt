package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class GeoQueryTest {

    @Test
    fun `a hospital becomes a geo search uri`() {
        val uri = GeoQuery.uri("Allied Hospital", null)
        assertEquals("geo:0,0?q=Allied+Hospital", uri)
    }

    @Test
    fun `the city is appended to the search text`() {
        val uri = GeoQuery.uri("Allied Hospital", "Faisalabad")!!
        assertTrue(uri.contains("Allied+Hospital"))
        assertTrue(uri.contains("Faisalabad"))
    }

    @Test
    fun `a city already in the hospital name is not repeated`() {
        val uri = GeoQuery.uri("Faisalabad Hospital", "Faisalabad")!!
        assertFalse(uri.contains("%2C")) // no comma-separated second part
    }

    @Test
    fun `spaces and special characters are url encoded`() {
        val uri = GeoQuery.uri("Civil Hospital & Blood Bank", null)!!
        assertFalse(uri.contains(" "))
        assertTrue(uri.contains("%26"))
    }

    @Test
    fun `a blank hospital has nothing to navigate to`() {
        assertNull(GeoQuery.uri(null, "Lahore"))
        assertNull(GeoQuery.uri("   ", "Lahore"))
    }
}
