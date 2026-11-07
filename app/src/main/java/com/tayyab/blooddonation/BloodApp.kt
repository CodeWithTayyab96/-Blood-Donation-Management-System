package com.tayyab.blooddonation

import android.app.Application
import com.tayyab.blooddonation.util.ThemePrefs

/**
 * Application entry point. The only job is to apply the saved theme before
 * any Activity draws, so the first screen already opens in the right mode
 * (Light by default; Dark or System when the user picked one in Settings).
 */
class BloodApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applySaved(this)
    }
}
