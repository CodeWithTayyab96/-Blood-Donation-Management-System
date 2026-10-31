package com.tayyab.blooddonation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.BuildConfig
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.AppUpdater
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.RequestAlertScheduler
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001
    }

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        if (!Session.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        db = DbHelper(this)

        // Donors get the ~15-minute request poll; managers do not. Any poll left
        // behind by a previous donor session is stopped here.
        if (Session.role == Session.ROLE_DONOR && Session.donorId != null) {
            RequestAlertScheduler.schedule(this)
            askForNotificationPermission()
        } else {
            RequestAlertScheduler.cancel(this)
        }

        findViewById<TextView>(R.id.tvSignedIn).text = "Signed in as ${Session.label()}"
        findViewById<Button>(R.id.btnDonors).setOnClickListener {
            startActivity(Intent(this, DonorListActivity::class.java))
        }
        findViewById<Button>(R.id.btnStock).setOnClickListener {
            startActivity(Intent(this, StockActivity::class.java))
        }
        findViewById<Button>(R.id.btnRequests).setOnClickListener {
            startActivity(Intent(this, RequestListActivity::class.java))
        }
        findViewById<Button>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            checkForUpdate()
        }
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            // Stop the request poll before the session is cleared, so a signed
            // out device does not keep waking up for alerts.
            RequestAlertScheduler.cancel(this)
            Session.logout(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    /**
     * Android 13+ requires a runtime permission for notifications. Without it
     * every alert is silently dropped, so ask once on the dashboard. A refusal
     * is not an error: the app works, the donor just never gets alerts.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        Background.run {
            when (val result = AppUpdater.checkForUpdate()) {
                is AppUpdater.CheckResult.UpdateAvailable -> runOnUiThread {
                    showUpdateDialog(result.info)
                }
                is AppUpdater.CheckResult.UpToDate -> runOnUiThread {
                    Toast.makeText(
                        this,
                        "You are up to date (v${BuildConfig.VERSION_NAME}).",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is AppUpdater.CheckResult.Error -> runOnUiThread {
                    Toast.makeText(
                        this,
                        "Update check failed: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(info: AppUpdater.UpdateInfo) {
        val notes = if (info.notes.isBlank()) {
            "A new version is available."
        } else {
            info.notes.take(500)
        }
        AlertDialog.Builder(this)
            .setTitle("Update available: v${info.versionName}")
            .setMessage(
                "You have v${BuildConfig.VERSION_NAME}.\n\n$notes\n\n" +
                    "The new version will be downloaded from GitHub Releases " +
                    "and opened in the system installer. Your donor data stays on this phone."
            )
            .setPositiveButton("Download and install") { _, _ ->
                startUpdateDownload(info)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdateDownload(info: AppUpdater.UpdateInfo) {
        if (!AppUpdater.canInstallPackages(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow installing updates")
                .setMessage(
                    "Android needs your permission before this app can install " +
                        "its own updates. Turn on \"Allow from this source\" on the " +
                        "next screen, then press Check for updates again."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    AppUpdater.openInstallPermissionScreen(this)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        // A plain non-cancellable dialog showing the percentage, instead of the
        // deprecated android.app.ProgressDialog (which renders unstyled under a
        // Material theme and is removed in newer SDKs).
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading update")
            .setMessage("Downloading v${info.versionName}...")
            .setCancelable(false)
            .create()
        progress.show()
        Background.run {
            AppUpdater.downloadAndInstall(
                this,
                info,
                onProgress = { percent ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            progress.setMessage("Downloading v${info.versionName}... $percent%")
                        }
                    }
                },
                onError = { message ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) progress.dismiss()
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            )
            runOnUiThread { if (!isFinishing && !isDestroyed) progress.dismiss() }
        }
    }

    private fun refresh() {
        // Show the local copy immediately, then quietly refresh it from
        // Supabase in the background and re-render. If Supabase is not
        // configured or unreachable, the local numbers simply stay — the
        // dashboard never waits on the network and never crashes.
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            Background.run {
                SupabaseSync.pullAll(db)
                runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
            }
        }
    }

    private fun renderFromLocal() {
        findViewById<TextView>(R.id.tvStatDonors).text = db.donorCount().toString()
        findViewById<TextView>(R.id.tvStatUnits).text = db.totalUnitsInStock().toString()
        findViewById<TextView>(R.id.tvStatRequests).text = db.pendingRequestCount().toString()
        findViewById<TextView>(R.id.tvStatMonth).text = db.donationsThisMonth().toString()
        val stock = db.stockLevels()
        findViewById<TextView>(R.id.tvStockSummary).text =
            BloodCompatibility.ALL_GROUPS.joinToString("\n") { g -> "$g : ${stock[g] ?: 0} unit(s)" }
    }
}
