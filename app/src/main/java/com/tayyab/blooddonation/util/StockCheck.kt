package com.tayyab.blooddonation.util

/**
 * Decides whether a request can be fulfilled from stock, keeping "the server
 * says there are N units" strictly apart from "the server could not be asked".
 *
 * That distinction is the whole point. A failed stock lookup used to fall
 * through to the same "not enough units in stock" message as a genuine
 * shortage, which states something about the server's stock that the app had no
 * basis for — the user saw "In stock: 2" and "not enough units" at the same
 * time. [Decision.Unknown] exists so callers cannot report zero for a count they
 * never read.
 *
 * Pure Kotlin on purpose: no Android, so it can be unit tested directly.
 */
object StockCheck {

    sealed class Decision {
        /** The count was read and covers what the request needs. */
        object Sufficient : Decision()

        /** The count was read and really is too low. [available] is that count. */
        data class Short(val available: Int) : Decision()

        /** The count could not be read at all. Nothing is known about it. */
        object Unknown : Decision()
    }

    /**
     * @param available the server's count for the group, or null when it could
     *   not be read (offline, 401/403, or no row for that group).
     * @param needed the units the request asks for.
     */
    fun decide(available: Int?, needed: Int): Decision = when {
        available == null -> Decision.Unknown
        available < needed -> Decision.Short(available)
        else -> Decision.Sufficient
    }
}
