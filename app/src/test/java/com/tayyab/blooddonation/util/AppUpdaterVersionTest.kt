package com.tayyab.blooddonation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppUpdater.compareVersionNames] decides whether a GitHub release is newer
 * than the installed build when a release ships no update.json. Getting it
 * wrong means either never offering an update or nagging on every launch.
 *
 * Only the pure comparison is tested here; the network paths need a device.
 */
class AppUpdaterVersionTest {

    @Test
    fun `identical versions are equal`() {
        assertEquals(0, AppUpdater.compareVersionNames("1.4", "1.4"))
    }

    @Test
    fun `a trailing zero component is ignored`() {
        assertEquals(0, AppUpdater.compareVersionNames("1.4", "1.4.0"))
        assertEquals(0, AppUpdater.compareVersionNames("1.4.0", "1.4"))
    }

    /** The classic string-comparison trap: "1.10" must beat "1.2". */
    @Test
    fun `1_10 is newer than 1_2`() {
        assertTrue(AppUpdater.compareVersionNames("1.10", "1.2") > 0)
        assertTrue(AppUpdater.compareVersionNames("1.2", "1.10") < 0)
    }

    @Test
    fun `a higher major version wins`() {
        assertTrue(AppUpdater.compareVersionNames("2.0", "1.9.9") > 0)
        assertTrue(AppUpdater.compareVersionNames("1.9.9", "2.0") < 0)
    }

    /**
     * The contract is dotted numeric versions only. A git tag's leading "v" is
     * stripped by the caller (`tag.removePrefix("v")` in checkForUpdate), not
     * by this function: "v1.4" would split to ["v1", "4"], and "v1" is not a
     * number, so it would parse as [4] rather than [1, 4].
     */
    @Test
    fun `compares dotted numeric versions`() {
        assertTrue(AppUpdater.compareVersionNames("1.5", "1.4") > 0)
        assertTrue(AppUpdater.compareVersionNames("1.4", "1.5") < 0)
        assertEquals(0, AppUpdater.compareVersionNames("1.4", "1.4.0.0"))
    }

    @Test
    fun `non-numeric parts are dropped`() {
        // split(".", "-") then toIntOrNull(): "beta" falls away.
        assertEquals(0, AppUpdater.compareVersionNames("1.4-beta", "1.4"))
    }

    @Test
    fun `1_4_1 is newer than 1_4`() {
        assertTrue(AppUpdater.compareVersionNames("1.4.1", "1.4") > 0)
        assertTrue(AppUpdater.compareVersionNames("1.4", "1.4.1") < 0)
    }
}
