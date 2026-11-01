package com.tayyab.blooddonation.util

/**
 * Phase 4 client rules for the AI helper — everything here is pure and unit
 * tested, because the network call around it cannot be.
 *
 * Privacy rules, enforced HERE and mirrored by the server:
 *  - NO personal data is sent. An earlier version sent a first name, donation
 *    count, days since the last donation and blood group; none of it is needed
 *    to write a motivational line or answer a general question, and a name is
 *    personal data, so it is gone. The payload is the request kind and, for a
 *    question, the question itself — nothing else;
 *  - questions are clamped so a screen cannot become a chat dump;
 *  - replies are clamped so a card stays a card.
 */
object AiRules {

    /** The helper screen's visible-while-typing limit. */
    const val MAX_QUESTION_LENGTH = 500

    /** What the AI card may occupy. */
    const val MAX_MOTIVATION_LENGTH = 280

    /** What an answer may occupy on the helper screen. */
    const val MAX_REPLY_LENGTH = 600
    const val MAX_ANSWER_LENGTH = MAX_REPLY_LENGTH

    /**
     * Clamps and tidies a typed question: trims, collapses wild blank lines,
     * and hard-caps the length. The widget's android:maxLength covers most of
     * this; the clamp stays authoritative so the SERVER payload rules hold no
     * matter how the question was stored.
     */
    fun sanitizeQuestion(text: String): String {
        val cleaned = text.trim().replace(Regex("\n{3,}"), "\n\n")
        return if (cleaned.length <= MAX_QUESTION_LENGTH) cleaned
        else cleaned.take(MAX_QUESTION_LENGTH)
    }

    /**
     * Clamps a reply from the provider. Returns null for blank replies, so
     * callers know there is still nothing to show and should keep the local
     * fallback text.
     */
    fun cleanReply(text: String, limit: Int): String? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= limit) cleaned else cleaned.take(limit)
    }

    /**
     * Once-a-day cadence for the home card: fetch when no message is cached
     * for today, but not again on the calendar day a fetch already failed
     * (a server without an OpenRouter key would otherwise be pinged on every
     * dashboard open).
     */
    fun shouldFetch(
        todayEpochDay: Long,
        cachedDay: Long?,
        lastFailedDay: Long?,
        messageCached: Boolean
    ): Boolean {
        if (messageCached && cachedDay == todayEpochDay) return false
        return lastFailedDay != todayEpochDay
    }
}
