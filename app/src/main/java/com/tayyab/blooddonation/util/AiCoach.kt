package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The signed-in user's bridge to the `ai-coach` Supabase Edge Function (same
 * style as [SupabaseClient]: plain HttpURLConnection, no new dependency).
 *
 * Contract with the function: `POST {"kind":"quote"}` or
 * `POST {"kind":"question","question":"…"}`, Authorization = the USER's
 * Supabase access token. The function runs with verify_jwt ON and resolves the
 * caller from that token. A session with no token never calls it at all.
 *
 * **No personal data is sent.** An earlier version sent a first name, donation
 * count, days since the last donation and blood group. None of it is needed —
 * and a name is personal data — so the payload is now just the kind and, for a
 * question, the sanitised question. [payloadJson] builds the body by hand,
 * precisely so the exact bytes on the wire are unit-testable (org.json is
 * stubbed out in local JVM tests and cannot be trusted there).
 *
 * Every call here is blocking, so it must run on a background thread; nothing
 * here ever throws.
 */
object AiCoach {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Escapes the characters that would otherwise break the JSON string. */
    fun jsonEscape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code >= 32) append(ch)
            }
        }
    }

    /**
     * THE wire format — the only thing that leaves the phone. Pure function,
     * unit tested (AiCoachPayloadTest) against the contract in
     * supabase/functions/ai-coach/index.ts.
     */
    fun payloadJson(kind: String, question: String? = null): String {
        return buildString {
            append("{\"kind\":\"").append(jsonEscape(kind)).append("\"")
            if (kind == "question" && question != null) {
                append(",\"question\":\"").append(jsonEscape(question)).append("\"")
            }
            append("}")
        }
    }

    /**
     * What came back from the "Can I donate?" helper.
     *
     * The distinction matters on screen: a limit the user hit is information
     * ("five a day, and it resets tomorrow"), a helper that was never
     * configured is a different thing entirely, and neither should be shown as
     * a generic failure.
     */
    sealed class Answer {
        /** The helper's reply, already trimmed and length-checked. */
        data class Ok(val text: String) : Answer()

        /** 429: the user's own five a day, or the project's daily cap. */
        data class Limited(val message: String) : Answer()

        /** 404/503: the function is not deployed, or has no API key. */
        data class NotSetUp(val message: String) : Answer()

        /** Anything else: offline, provider down, unexpected status. */
        data class Failed(val message: String) : Answer()
    }

    /** Reads the server's `error` field without org.json (stubbed in tests). */
    internal fun errorMessage(body: String): String? {
        val m = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        return m?.groupValues?.get(1)
            ?.replace("\\n", " ")
            ?.replace("\\\"", "\"")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun url(): String =
        BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/ai-coach"

    /**
     * One call to the function. Returns (HTTP status, body). Status -1 means
     * the request never completed — no connection, or no session.
     */
    private fun request(kind: String, question: String?): Pair<Int, String?> {
        val token = Session.accessToken
        if (token.isNullOrBlank() || BuildConfig.SUPABASE_URL.isBlank()) return -1 to null
        val body = payloadJson(kind, question)
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
                    out.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code == 200) connection.inputStream else connection.errorStream
                code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            -1 to null
        } catch (_: Exception) {
            -1 to null
        }
    }

    internal fun replyText(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val m = Regex("\"reply\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        return m?.groupValues?.get(1)
            ?.replace("\\n", " ")
            ?.replace("\\\"", "\"")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Today's motivational line, from the shared daily quote bank.
     *
     * Null on ANY failure — offline, not configured, over the daily cap, or the
     * model returning nothing usable. The caller then shows its local fact of
     * the day, which is always a correct home screen; an AI card is a bonus,
     * never a requirement. That is why this returns a nullable String rather
     * than an error: there is nothing here the user needs to act on.
     */
    fun quote(): String? {
        val (code, body) = request("quote", null)
        if (code != 200) return null
        return replyText(body)?.let { AiRules.cleanReply(it, AiRules.MAX_MOTIVATION_LENGTH) }
    }

    /**
     * The "Can I donate?" helper's answer. [question] must already be
     * sanitised through [AiRules.sanitizeQuestion].
     */
    fun answer(question: String): Answer {
        val (code, body) = request("question", question)
        val serverMessage = body?.let { errorMessage(it) }
        return when {
            code == 200 -> {
                val text = replyText(body)?.let {
                    AiRules.cleanReply(it, AiRules.MAX_ANSWER_LENGTH)
                }
                if (text.isNullOrBlank()) {
                    Answer.Failed("The helper did not return an answer. Try again shortly.")
                } else {
                    Answer.Ok(text)
                }
            }
            code == 429 -> Answer.Limited(
                serverMessage
                    ?: "You have reached today's limit for the helper. It resets tomorrow."
            )
            // 404: the function is not deployed. 503: it is deployed but has no
            // API key, or the quote bank table is missing. Both are setup, and
            // both must read as setup rather than as a broken app.
            code == 404 || code == 503 -> Answer.NotSetUp(
                "The AI helper is not set up yet. Everything else in the app works without it."
            )
            code < 0 -> Answer.Failed("No internet connection.")
            else -> Answer.Failed(serverMessage ?: "The helper is unavailable (HTTP $code).")
        }
    }
}
