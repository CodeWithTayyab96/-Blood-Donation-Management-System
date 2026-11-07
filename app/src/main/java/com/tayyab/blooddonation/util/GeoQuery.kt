package com.tayyab.blooddonation.util

import java.net.URLEncoder

/**
 * Builds the `geo:` intent URI for the Navigate button.
 *
 * No Google Maps SDK, no API key, no location permission: an ACTION_VIEW on a
 * geo URI hands the query to whatever maps app the phone already has. Pure
 * Kotlin (java.net only), so the URI rules are unit tested on the JVM.
 */
object GeoQuery {

    /**
     * `geo:0,0?q=<hospital, city>` — the `0,0` part means "no known
     * coordinates, search this text instead". A blank hospital has nothing to
     * search for, so it returns null and the button is hidden.
     */
    fun uri(hospital: String?, city: String?): String? {
        val place = hospital?.trim().orEmpty()
        if (place.isEmpty()) return null
        val query = buildList {
            add(place)
            city?.trim()?.takeIf { it.isNotEmpty() && !place.contains(it, ignoreCase = true) }
                ?.let { add(it) }
        }.joinToString(", ")
        return "geo:0,0?q=${URLEncoder.encode(query, "UTF-8")}"
    }
}
