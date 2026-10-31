package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 4: the signed-in user's bridge to the `ai-coach` Supabase Edge
 * Function (same style as [SupabaseClient]: plain HttpURLConnection +
 * org.json, no new dependency).
 *
 * Contract with the function (written before the first deploy, so both sides
 * agree): POST JSON `{kind, facts:{name, donations, days_since_last_donation,
 * blood_group}, question?}`, Authorization = the USER's Supabase access
 * token — the function runs with verify_jwt ON and resolves the caller from
 * it. A local-only session (demo manager, offline fallback login) has no
 * token and therefore never calls the provider; callers handle a null return
 * by keeping the local fact bank / fallback text.
 *
 * The four facts above are the entire payload that reaches the provider:
 * nothing else about the user (phone, city, age, weight, gender, id) is
 * ever serialised, which is also unit tested. Every call is blocking, so it
 * must run on a background thread; nothing here ever throws.
 */
object AiCoach {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** The four neutral facts; see [AiRules]' privacy note. */
    data class Facts(
        val firstName: String,
        val donationCount: Int,
        val daysSinceLastDonation: Long?,   // null = no donation on record
        val bloodGroup: String
    )

    private fun url(): String =
        BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/ai-coach"

    private fun request(facts: Facts, kind: String, question: String?): JSONObject? {
        val token = Session.accessToken
        if (token.isNullOrBlank() || BuildConfig.SUPABASE_URL.isBlank()) return null
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
        return try {
            val connection = URL(url()).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code == 200) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (code != 200 || text.isNullOrBlank()) return null
                return JSONObject(text)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Today's one-line personalised encouragement. Null on any failure; the
     * dashboard then shows its local fact of the day, as it always has.
     */
    fun motivation(facts: Facts): String? {
        val json = try { request(facts, "motivation", null) } catch (e: Exception) { null } ?: return null
        return AiRules.cleanReply(json.optString("reply", ""), AiRules.MAX_MOTIVATION_LENGTH)
    }

    /**
     * The "Can I donate?" helper's answer. [question] must already be
     * sanitised through [AiRules.sanitizeQuestion]. Null on any failure —
     * the screen shows a standard "ask a licensed blood bank" message.
     */
    fun answer(facts: Facts, question: String): String? {
        val json = try { request(facts, "question", question) } catch (e: Exception) { null } ?: return null
        return AiRules.cleanReply(json.optString("reply", ""), AiRules.MAX_REPLY_LENGTH)
    }
}
