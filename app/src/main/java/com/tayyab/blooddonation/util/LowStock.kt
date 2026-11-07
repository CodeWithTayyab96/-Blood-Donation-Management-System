package com.tayyab.blooddonation.util

/**
 * The low-stock rule behind the manager's CSV report. Pure so the boundary
 * (<= threshold, not <) and the ordering are unit tested.
 */
object LowStock {

    const val DEFAULT_THRESHOLD = 2

    /** Groups at or below [threshold], lowest first, then by name for stability. */
    fun groups(
        stock: Map<String, Int>,
        threshold: Int = DEFAULT_THRESHOLD
    ): List<Pair<String, Int>> = stock
        .filterValues { it <= threshold }
        .toList()
        .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
}
