package com.tayyab.blooddonation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.util.LanguagePrefs
import com.tayyab.blooddonation.util.ThemePrefs
import com.tayyab.blooddonation.util.TipSchedule
import com.tayyab.blooddonation.util.TipScheduler

/**
 * Settings. Currently one switch — the optional daily tip — plus a way through
 * to Android's own notification settings, because a tip that is switched on in
 * the app still needs the system permission to actually arrive.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Settings"

        // Theme (Light / Dark / System). Same pattern as the language picker
        // below: set the checked state first, attach the listener afterwards,
        // and only apply when the choice actually changed. Applying recreates
        // the activities, so the new theme shows at once.
        val rgTheme = findViewById<RadioGroup>(R.id.rgTheme)
        rgTheme.check(
            when (ThemePrefs.savedMode(this)) {
                ThemePrefs.DARK -> R.id.rbThemeDark
                ThemePrefs.SYSTEM -> R.id.rbThemeSystem
                else -> R.id.rbThemeLight
            }
        )
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeDark -> ThemePrefs.DARK
                R.id.rbThemeSystem -> ThemePrefs.SYSTEM
                else -> ThemePrefs.LIGHT
            }
            if (mode != ThemePrefs.savedMode(this)) {
                ThemePrefs.apply(this, mode)
            }
        }

        val swTips = findViewById<SwitchCompat>(R.id.swTips)
        // Set the listener after the initial value, or restoring the state fires
        // a spurious enable/disable.
        swTips.isChecked = TipSchedule.isEnabled(this)
        swTips.setOnCheckedChangeListener { _, checked ->
            TipSchedule.setEnabled(this, checked)
            if (checked) TipScheduler.schedule(this) else TipScheduler.cancel(this)
            updateState()
        }

        // Per-app language. AppCompatDelegate persists the choice and recreates
        // the activities, so set the checked state first and attach the
        // listener afterwards — exactly like the tips switch above.
        val rgLanguage = findViewById<RadioGroup>(R.id.rgLanguage)
        val saved = LanguagePrefs.savedTag(this)
        rgLanguage.check(
            if (saved == LanguagePrefs.URDU) R.id.rbUrdu else R.id.rbEnglish
        )
        rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val tag = if (checkedId == R.id.rbUrdu) LanguagePrefs.URDU
                      else LanguagePrefs.ENGLISH
            if (tag != LanguagePrefs.savedTag(this)) {
                LanguagePrefs.apply(this, tag)
            }
        }

        findViewById<Button>(R.id.btnConnectionCheck).setOnClickListener {
            startActivity(android.content.Intent(this, ConnectionCheckActivity::class.java))
        }

        findViewById<Button>(R.id.btnDeleteAccount).setOnClickListener {
            // Managers are refused server-side, so they are not stopped here —
            // the refusal message explains why, which is more useful than a
            // button that does nothing.
            startActivity(
                android.content.Intent(this, DeleteAccountActivity::class.java)
            )
        }

        findViewById<Button>(R.id.btnNotificationSettings).setOnClickListener {
            openSystemNotificationSettings()
        }

        updateState()
    }

    override fun onResume() {
        super.onResume()
        // The permission may have been granted while the user was away.
        updateState()
    }

    private fun updateState() {
        val tv = findViewById<TextView>(R.id.tvNotificationState)
        val granted = notificationsAllowed()
        tv.text = when {
            !granted ->
                "Notifications are blocked for this app in Android settings, so no tip can arrive. " +
                    "Urgent blood request alerts are blocked too."
            TipSchedule.isEnabled(this) ->
                "Tips are on. One may arrive during the day, never at night."
            else ->
                "Tips are off. Urgent blood request alerts are separate and still apply."
        }
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openSystemNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }
}
