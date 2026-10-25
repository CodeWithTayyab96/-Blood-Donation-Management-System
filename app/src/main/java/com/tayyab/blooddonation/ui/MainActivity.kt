package com.tayyab.blooddonation.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.BuildConfig
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.AppUpdater
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session

class MainActivity : AppCompatActivity() {

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
            Session.logout(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        Thread {
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
        }.start()
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
        val progress = ProgressDialog(this).apply {
            setTitle("Downloading update")
            setMessage("Downloading v${info.versionName}...")
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }
        Thread {
            AppUpdater.downloadAndInstall(
                this,
                info,
                onProgress = { percent -> runOnUiThread { progress.progress = percent } },
                onError = { message ->
                    runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            )
            runOnUiThread { progress.dismiss() }
        }.start()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.tvStatDonors).text = db.donorCount().toString()
        findViewById<TextView>(R.id.tvStatUnits).text = db.totalUnitsInStock().toString()
        findViewById<TextView>(R.id.tvStatRequests).text = db.pendingRequestCount().toString()
        findViewById<TextView>(R.id.tvStatMonth).text = db.donationsThisMonth().toString()
        val stock = db.stockLevels()
        findViewById<TextView>(R.id.tvStockSummary).text =
            BloodCompatibility.ALL_GROUPS.joinToString("\n") { g -> "$g : ${stock[g] ?: 0} unit(s)" }
    }
}
