package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

/**
 * How the app reads the ai-coach's answers.
 *
 * The point of these is the difference between "not set up" and "broken". A
 * 503 with no API key and a 502 from a provider outage are different facts and
 * must read differently on screen; a 429 has to tell the user the limit resets
 * tomorrow instead of looking like a fault. Parsing is hand-rolled (org.json is
 * stubbed on the JVM test classpath), so it is pinned down here too.
 */
class AiCoachParsingTest {

    @Test
    fun `a reply is read out of a successful body`() {
        assertEquals(
            "Every donation can help up to three patients.",
            AiCoach.replyText("""{"reply":"Every donation can help up to three patients.","source":"bank"}""")
        )
    }

    @Test
    fun `escaped characters in a reply are unescaped`() {
        assertEquals(
            "You can donate again after about 90 days.",
            AiCoach.replyText("""{"reply":"You can donate again after about 90 days."}""")
        )
        assertTrue(
            AiCoach.replyText("""{"reply":"Line one.\nLine two."}""")!!.contains("Line one. Line two.")
        )
    }

    @Test
    fun `a body with no reply yields nothing`() {
        assertNull(AiCoach.replyText("""{"error":"no usable quote was generated"}"""))
        assertNull(AiCoach.replyText(null))
        assertNull(AiCoach.replyText(""))
    }

    @Test
    fun `the server's own error message is recovered`() {
        assertEquals(
            "you have used all 5 questions for today — it resets tomorrow",
            AiCoach.errorMessage(
                """{"error":"you have used all 5 questions for today — it resets tomorrow","limit":"user"}"""
            )
        )
    }

    @Test
    fun `a missing or malformed error is not a crash`() {
        assertNull(AiCoach.errorMessage("{}"))
        assertNull(AiCoach.errorMessage("not json"))
        assertNull(AiCoach.errorMessage("<html>404</html>"))
    }
}
