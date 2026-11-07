package com.tayyab.blooddonation.util

/**
 * Builds the plain text behind "Share request" (ACTION_SEND).
 *
 * Pure Kotlin on purpose: every label arrives already translated from
 * strings.xml, so this object only decides the ORDER of the lines and which
 * optional ones are omitted. No user-facing wording lives here — that is what
 * makes the share text translatable, and what makes it unit-testable on the JVM.
 *
 * Optional lines are passed as null (or blank) when the value is missing. A
 * request with no city must not read "City: " at the bottom of a WhatsApp
 * message, and a contact number the viewer is not allowed to see must not
 * appear as "Contact: null".
 */
object ShareText {

    /**
     * Joins the non-blank lines in the order given, one per line.
     *
     * @param heading  e.g. "Blood needed — please share"
     * @param group    e.g. "Blood group: O-"
     * @param units    e.g. "Units needed: 2"
     * @param hospital e.g. "Hospital: Allied Hospital"
     * @param city     null when the request has no city
     * @param contact  null when the viewer may not see the number
     * @param footer   e.g. "Shared from Blood Donation"
     */
    fun requestText(
        heading: String,
        group: String,
        units: String,
        hospital: String,
        city: String?,
        contact: String?,
        footer: String
    ): String = listOf(heading, group, units, hospital, city, contact, footer)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString("\n")
}
