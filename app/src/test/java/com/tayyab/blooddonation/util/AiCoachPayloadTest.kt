package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4 privacy and wire contract: AiCoach.payloadJson is the ONLY thing
 * that travels to the AI provider, built by hand (pure string work) exactly
 * so these tests can run in the JVM without the stubbed org.json.
 *
 * The contract lives in supabase/functions/ai-coach/index.ts: keys
 * kind/facts{?question}, with facts = name, donations,
 * days_since_last_donation, blood_group — and nothing else.
 */
class AiCoachPayloadTest {

    private val facts = AiCoach.Facts(
        firstName = "Tayyab Imran",
        donationCount = 4,
        daysSinceLastDonation = 128L,
        bloodGroup = "B+"
    )

    @Test
    fun `motivation payload carries only kind and the four facts`() {
        val json = AiCoach.payloadJson("motivation", facts)
        // Top-level keys: kind, facts (no question).
        assertTrue(json.startsWith("""{"kind":"motivation""""))
        assertFalse(json.contains("\"question\""))
        // The four whitelisted facts, in contract order.
        assertTrue(
            json.contains(
                """"facts":{"name":"Tayyab Imran","donations":4,""" +
                """"days_since_last_donation":128,"blood_group":"B+"}"""
            )
        )
        // Length sanity: nothing else is smuggled in.
        assertFalse(json.contains("phone") || json.contains("city") ||
            json.contains("age") || json.contains("weight") ||
            json.contains("gender") || json.contains("auth"))
    }

    @Test
    fun `question payload adds exactly the question key`() {
        val json = AiCoach.payloadJson("question", facts, "Can I donate today?")
        assertEquals(
            """{"kind":"question","facts":{"name":"Tayyab Imran","donations":4,""" +
            """"days_since_last_donation":128,"blood_group":"B+"},""" +
            """"question":"Can I donate today?"}""",
            json
        )
    }

    @Test
    fun `days since last donation can be null on the wire`() {
        val json = AiCoach.payloadJson("motivation", facts.copy(daysSinceLastDonation = null))
        assertTrue(json.contains("\"days_since_last_donation\":null"))
    }

    @Test
    fun `negative donation counts are clamped`() {
        val json = AiCoach.payloadJson("motivation", facts.copy(donationCount = -3))
        assertTrue(json.contains("\"donations\":0"))
    }

    @Test
    fun `quotes backslashes and newlines are escaped so the body stays valid JSON`() {
        val json = AiCoach.payloadJson(
            "question", facts,
            "Can I \"donate\" on\nline\tone\\path?"
        )
        // Structural quotes are NOT escaped — only the characters inside the
        // string value are: " -> \", newline -> \n, tab -> \t, \ -> \\.
        assertTrue(
            json.contains(
                ""","question":"Can I \"donate\" on\nline\tone\\path?"}"""
            )
        )
    }

    @Test
    fun `control characters are dropped, multibyte letters survive`() {
        val json = AiCoach.payloadJson(
            "motivation", facts.copy(firstName = "Zo\u0007e")
        )
        assertFalse(json.contains("\u0007"))
        assertTrue(json.contains("Zoe"))
        // A non-ASCII first name passes through unharmed.
        assertTrue(AiCoach.payloadJson("motivation", facts.copy(firstName = "Zoë"))
            .contains("Zoë"))
    }

    @Test
    fun `jsonEscape covers the dangerous set`() {
        assertEquals("""a\\b\"c\nd\re\tf""", AiCoach.jsonEscape("a\\b\"c\nd\re\tf"))
        assertEquals("", AiCoach.jsonEscape("\u0001\u0002"))
    }
}
