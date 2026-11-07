package com.tayyab.blooddonation.util

/**
 * The lines drawn on a donation certificate.
 *
 * Pure so the layout can be unit tested: the PDF renderer receives an ordered
 * list of already-translated strings and never decides what to say. Blank
 * values are dropped rather than rendered as "Blood group: ".
 */
object CertificateText {

    fun lines(
        title: String,
        nameLabel: String,
        name: String,
        groupLabel: String,
        group: String,
        dateLabel: String,
        date: String,
        thanks: String
    ): List<String> {
        val out = mutableListOf<String>()
        // The title and the thanks line are always shown (when non-blank); the
        // labelled facts are dropped entirely when their value is blank, so the
        // PDF never renders a dangling "Blood group: ".
        out.add(title.trim())
        if (name.isNotBlank()) out.add("$nameLabel: ${name.trim()}")
        if (group.isNotBlank()) out.add("$groupLabel: ${group.trim()}")
        if (date.isNotBlank()) out.add("$dateLabel: ${date.trim()}")
        out.add(thanks.trim())
        return out.filter { it.isNotEmpty() }
    }
}
