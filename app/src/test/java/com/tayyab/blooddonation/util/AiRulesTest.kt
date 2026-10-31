package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

/** Phase 4 rules: privacy of the payload, question/reply clamps, daily card cadence. */
class AiRulesTest {

    // ------------------------------------------------------- question clamp

    @Test
    fun `questions are trimmed and capped`() {
        assertEquals("Can I donate today?", AiRules.sanitizeQuestion("  Can I donate today?  "))
        val long = "a".repeat(AiRules.MAX_QUESTION_LENGTH + 100)
        assertEquals(AiRules.MAX_QUESTION_LENGTH, AiRules.sanitizeQuestion(long).length)
    }

    @Test
    fun `wild blank lines collapse but text survives`() {
        val messy = "Q1\n\n\n\n\nQ2"
        assertEquals("Q1\n\nQ2", AiRules.sanitizeQuestion(messy))
    }

    // ------------------------------------------------------------ replies

    @Test
    fun `blank replies fall back to null so the local text stays`() {
        assertNull(AiRules.cleanReply("   ", AiRules.MAX_MOTIVATION_LENGTH))
        assertNull(AiRules.cleanReply("", AiRules.MAX_MOTIVATION_LENGTH))
    }

    @Test
    fun `replies are trimmed not padded`() {
        assertEquals("Drink water.", AiRules.cleanReply("  Drink water.  ", 100))
    }

    @Test
    fun `over-long replies are clamped so a card stays a card`() {
        val answer = "b".repeat(AiRules.MAX_REPLY_LENGTH + 50)
        assertEquals(AiRules.MAX_REPLY_LENGTH, AiRules.cleanReply(answer, AiRules.MAX_REPLY_LENGTH)!!.length)
    }

    // ------------------------------------------------------------- cadence

    @Test
    fun `ya cached message for today skips fetch`() {
        assertFalse(AiRules.shouldFetch(19_000, 19_000, null, messageCached = true))
    }

    @Test
    fun `a failed day is not retried until the next calendar day`() {
        assertFalse(AiRules.shouldFetch(19_000, 18_999, 19_000, messageCached = false))
        assertTrue(AiRules.shouldFetch(19_001, 18_999, 19_000, messageCached = false))
    }

    @Test
    fun `no cache and no failure fetches`() {
        assertTrue(AiRules.shouldFetch(19_000, null, null, messageCached = false))
        // Yesterday's success does not block today.
        assertTrue(AiRules.shouldFetch(19_001, 19_000, null, messageCached = true))
    }
}
