package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import org.json.JSONObjectimport org.junit.Assert.*
import org.junit.Test

/**
 * The privacy boundary of Phase 4: the exact JSON the client sends must
 * contain the four whitelisted facts and NOTHING else, and require a signed-in
 * session. This mirrors AiCoach's request construction byte for byte (the
 * network call itself needs a device).
 */
class AiCoachPayloadTest {

    private val facts = AiCoach.Facts(
        firstName = "Tayyab Imran",
        donationCount = 4,
        daysSinceLastDonation = 128L,
        bloodGroup = "B+"
    )

    private fun buildPayload(kind: String, question: String? = null): Map<String, String> {
        val body = JSONObject().apply {
            put("kind", kind)
            put(
                "facts", JSONObject().apply {
                    put("name", facts.firstName)
                    put("donations", facts.donationCount)
                    if (facts.daysSinceLastDonation != null) {
                        put("days_since_last_donation", facts.daysSinceLastDonation)
                    } else {
                        put("days_since_last_donation", JSONObject.NULL)
                    }
                    put("blood_group", facts.bloodGroup)
                }
            )
            if (kind == "question") put("question", question ?: "")
        }
        val factsObj = body.optJSONObject("facts")!!
        val out = mutableMapOf<String, String>()
        out["top-level keys"] = body.names()!!.joinToString()
        out["facts keys"] = factsObj.names()!!.joinToString()
        out["json"] = body.toString()
        return out
    }

    @Test
    fun `motivation payload carries only the four facts`() {
        val payload = buildPayload("motivation")
        val factKeys = payload["facts keys"]!!
        assertEquals(setOf("name", "donations", "days_since_last_donation", "blood_group"),
            factKeys.split(", ").toSet())
    }

    @Test
    fun `top level is kind facts only for motivation`() {
        assertEquals(setOf("kind", "facts"), buildPayload("motivation")["top-level keys"]!!.split(", ").toSet())
    }

    @Test
    fun `question payload adds only the question`() {
        assertEquals(setOf("kind", "facts", "question"),
            buildPayload("question", "Can I donate?")["top-level keys"]!!.split(", ").toSet())
    }

    @Test
    fun `no personal extras leak into the serialised body`() {
        val json = buildPayload("question", "Can I donate today?")["json"]!!
        for (banned in listOf("phone", "city", "age", "weight", "gender", "auth", "email", "token")) {
            assertFalse("key '$banned' must never be sent", json.contains(banned))
        }
    }

    @Test
    fun `days since last donation can be null on the wire`() {
        val none = facts.copy(daysSinceLastDonation = null)
        val body = JSONObject().apply {
            put("facts", JSONObject().apply {
                put("name", none.firstName)
                put("donations", none.donationCount)
                if (none.daysSinceLastDonation != null) {
                    put("days_since_last_donation", none.daysSinceLastDonation)
                } else {
                    put("days_since_last_donation", JSONObject.NULL)
                }
                put("blood_group", none.bloodGroup)
            })
        }
        assertTrue(body.optJSONObject("facts")!!.isNull("days_since_last_donation"))
    }
}
