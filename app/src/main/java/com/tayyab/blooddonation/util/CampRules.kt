package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.model.Camp
import java.time.LocalDate

/**
 * Phase 3: blood-camp list helpers.
 *
 * Pure date math so the upcoming / past split and ordering are unit tested with
 * fixed "today" values rather than the device clock.
 */
object CampRules {

    /** A camp is upcoming when its date is strictly after [asOf]. */
    fun isUpcoming(camp: Camp, asOf: LocalDate): Boolean =
        runCatching { LocalDate.parse(camp.campDate) }.getOrNull()?.isAfter(asOf) ?: false

    /** A camp is past when its date is strictly before [asOf]. */
    fun isPast(camp: Camp, asOf: LocalDate): Boolean =
        runCatching { LocalDate.parse(camp.campDate) }.getOrNull()?.isBefore(asOf) ?: false

    /** Ascending by camp date; unparseable dates sort last. */
    fun sortByDate(camps: List<Camp>): List<Camp> =
        camps.sortedBy { runCatching { LocalDate.parse(it.campDate) }.getOrNull() ?: LocalDate.MAX }
}
