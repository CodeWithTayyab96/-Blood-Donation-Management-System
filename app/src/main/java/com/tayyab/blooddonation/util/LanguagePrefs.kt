package com.tayyab.blooddonation.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The in-app language choice (English / اردو).
 *
 * [AppCompatDelegate.setApplicationLocales] is the AndroidX per-app locale
 * API: it works on API 26 (our minSdk) as well as on Android 13+'s system
 * picker, and it stores the choice itself so it survives a restart without an
 * Application subclass. We keep our own copy of the tag only so Settings can
 * show which radio button is selected.
 *
 * An empty LocaleListCompat means "follow the system language" — that is the
 * English default. Urdu is RTL; the manifest already declares
 * android:supportsRtl="true" and the touched layouts use start/end, so the
 * whole screen mirrors without any per-view changes.
 */
object LanguagePrefs {

    const val ENGLISH = "en"
    const val URDU = "ur"

    private const val PREFS = "app_language"
    private const val KEY_TAG = "language_tag"

    fun savedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, ENGLISH) ?: ENGLISH

    /** Applies and persists [tag]. Unknown values fall back to English. */
    fun apply(context: Context, tag: String) {
        val chosen = if (tag == URDU) URDU else ENGLISH
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TAG, chosen).apply()
        val locales = if (chosen == URDU) {
            LocaleListCompat.forLanguageTags(URDU)
        } else {
            LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
