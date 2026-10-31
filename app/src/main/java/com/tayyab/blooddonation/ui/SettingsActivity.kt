package com.tayyab.blooddonation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.tayyab.blooddonation.R
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

        val swTips = findViewById<SwitchCompat>(R.id.swTips)
        // Set the listener after the initial value, or restoring the state fires
        // a spurious enable/disable.
        swTips.isChecked = TipSchedule.isEnabled(this)
        swTips.setOnCheckedChangeListener { _, checked ->
            TipSchedule.setEnabled(this, checked)
            if (checked) TipScheduler.schedule(this) else TipScheduler.cancel(this)
            updateState()
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
