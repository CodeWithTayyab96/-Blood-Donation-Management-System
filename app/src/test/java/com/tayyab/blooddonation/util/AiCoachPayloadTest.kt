package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4 privacy and wire contract: AiCoach.payloadJson is the ONLY thing that
 * leaves the phone for the AI provider, built by hand (pure string work)
 * exactly so these tests can run in the JVM without the stubbed org.json.
 *
 * The contract lives in supabase/functions/ai-coach/index.ts:
 * `{kind}` or `{kind, question}` — and nothing else. An earlier version also
 * sent `facts{name, donations, days_since_last_donation, blood_group}`; those
 * are gone, because a motivational line does not need a name and an
 * eligibility answer must not be personalised. The tests below pin that down:
 * if a fact ever creeps back, these fail.
 */
class AiCoachPayloadTest {

    @Test
    fun `a quote request is just the kind`() {
        assertEquals("""{"kind":"quote"}""", AiCoach.payloadJson("quote"))
    }

    @Test
    fun `a question request adds exactly one key`() {
        assertEquals(
            """{"kind":"question","question":"Can I donate today?"}""",
            AiCoach.payloadJson("question", "Can I donate today?")
        )
    }

    @Test
    fun `no personal data of any kind reaches the provider`() {
        // The four facts the old version sent, plus everything that was never
        // sent, must not appear under any name.
        for (json in listOf(
            AiCoach.payloadJson("quote"),
            AiCoach.payloadJson("question", "Can I donate?")
        )) {
            for (leak in listOf(
                "facts", "name", "donations", "days_since", "blood_group",
                "phone", "city", "age", "weight", "gender", "auth", "email"
            )) {
                assertFalse("$leak must never be sent: $json", json.contains(leak))
            }
        }
    }

    @Test
    fun `a question is never sent for a quote request`() {
        // payloadJson ignores the question unless the kind is "question", so a
        // caller that passes one by mistake cannot leak it.
        assertEquals("""{"kind":"quote"}""", AiCoach.payloadJson("quote", "ignored"))
    }

    @Test
    fun `quotes backslashes and newlines are escaped so the body stays valid JSON`() {
        val json = AiCoach.payloadJson("question", "Can I \"donate\" on\nline\tone\\path?")
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
        val json = AiCoach.payloadJson("question", "Zo\u0007e")
        assertFalse(json.contains("\u0007"))
        assertTrue(json.contains("Zoe"))
        assertTrue(AiCoach.payloadJson("question", "کیا میں خون دے سکتا ہوں؟")
            .contains("کیا میں خون دے سکتا ہوں؟"))
    }

    @Test
    fun `jsonEscape covers the dangerous set`() {
        assertEquals("""a\\b\"c\nd\re\tf""", AiCoach.jsonEscape("a\\b\"c\nd\re\tf"))
        assertEquals("", AiCoach.jsonEscape("\u0001\u0002"))
    }
}
