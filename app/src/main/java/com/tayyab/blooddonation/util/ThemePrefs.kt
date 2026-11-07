package com.tayyab.blooddonation.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * The in-app theme choice (Light / Dark / System).
 *
 * Modelled on [LanguagePrefs]: we keep our own copy of the choice only so
 * Settings can show which radio button is selected. [AppCompatDelegate]
 * stores the night mode itself and recreates the activities when it changes,
 * so picking a theme applies at once. The theme is a DayNight parent, and the
 * dark palette lives in values-night, so the mode switch is a resource
 * reload, not a per-screen change.
 *
 * The default is Light, so nothing changes for anyone until they pick
 * something: the app was light-only before v1.23.
 */
object ThemePrefs {

    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    private const val PREFS = "app_theme"
    private const val KEY_MODE = "theme_mode"

    fun savedMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, LIGHT) ?: LIGHT

    /** Applies and persists [mode]. Unknown values fall back to Light. */
    fun apply(context: Context, mode: String) {
        val chosen = when (mode) {
            DARK -> DARK
            SYSTEM -> SYSTEM
            else -> LIGHT
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, chosen).apply()
        applyMode(chosen)
    }

    /** Applies the saved choice without writing it again; called at app start. */
    fun applySaved(context: Context) {
        applyMode(savedMode(context))
    }

    private fun applyMode(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }
}
