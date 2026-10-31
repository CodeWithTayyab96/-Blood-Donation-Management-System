package com.tayyab.blooddonation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tayyab.blooddonation.BuildConfig
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.AppUpdater
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.RequestAlertScheduler
import com.tayyab.blooddonation.util.RequestAlertWorker
import com.tayyab.blooddonation.util.RequestAlerts
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync
import com.tayyab.blooddonation.util.MotivationFacts
import com.tayyab.blooddonation.util.DonationStats
import java.time.LocalDate
import java.util.concurrent.TimeUnit

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

        // A local-only account (the seeded demo manager, or an offline fallback)
        // carries no Auth token, so every Supabase call goes out with the anon
        // key — which the authenticated-only RLS policies reject. Without this
        // note the app looks like it is syncing when nothing is.
        val tvSyncWarning = findViewById<TextView>(R.id.tvSyncWarning)
        if (SupabaseSync.isOnline && Session.accessToken.isNullOrBlank()) {
            tvSyncWarning.visibility = View.VISIBLE
            tvSyncWarning.text =
                "Offline-only session — this account has no Supabase login, so nothing " +
                    "syncs with the server. Sign in with an email address to see shared data."
        } else {
            tvSyncWarning.visibility = View.GONE
        }
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
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            checkForUpdate()
        }
        // Debug builds only. The release APK keeps this hidden and has no way
        // to trigger it.
        val btnCheckRequests = findViewById<Button>(R.id.btnCheckRequests)
        if (BuildConfig.DEBUG) {
            btnCheckRequests.visibility = View.VISIBLE
            btnCheckRequests.setOnClickListener { runRequestAlertCheckNow() }
        } else {
            btnCheckRequests.visibility = View.GONE
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
     * Android 13+ requires a runtime permission for notifications; without it
     * every alert is silently dropped.
     *
     * Asked exactly once, behind a short explanation, the first time a donor
     * reaches the dashboard. If they decline, the flag in [RequestAlerts] stops
     * the prompt coming back on every launch — they can enable it later in
     * system settings, and the app picks that up automatically because this
     * only runs while the permission is still missing.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (RequestAlerts.wasPermissionAsked(this)) return

        AlertDialog.Builder(this)
            .setTitle("Allow blood request alerts?")
            .setMessage(
                "This app can tell you when someone needs blood your group can give. " +
                    "It checks about every 15 minutes while you are online, and never " +
                    "notifies you twice for the same request.\n\n" +
                    "You can turn this off at any time in Android settings."
            )
            .setPositiveButton("Allow") { _, _ ->
                RequestAlerts.markPermissionAsked(this)
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS
                )
            }
            .setNegativeButton("Not now") { _, _ ->
                RequestAlerts.markPermissionAsked(this)
            }
            .show()
    }

    /**
     * Debug builds only: runs the alert worker immediately instead of waiting
     * for the next 15-minute poll, and reports what it did. Uses the same
     * worker as the periodic job, so it exercises the real code path.
     */
    private fun runRequestAlertCheckNow() {
        val request = OneTimeWorkRequestBuilder<RequestAlertWorker>()
            // Short backoff, so a failed pull retries while the developer is
            // still looking at the screen rather than 30 seconds later.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.SECONDS)
            .build()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueue(request)
        workManager.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val count = info.outputData.getInt(RequestAlertWorker.KEY_ALERT_COUNT, 0)
                    Toast.makeText(
                        this,
                        if (count == 0) "No new alerts" else "$count alert(s) sent",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                WorkInfo.State.FAILED -> {
                    Toast.makeText(this, "Alert check failed", Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
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

        // Daily motivation card: one fact per day, stable for the day and the
        // same on every device (derived from the epoch day, not wall-clock text).
        findViewById<TextView>(R.id.tvFact).text =
            MotivationFacts.factFor(LocalDate.now().toEpochDay())

        // Donor milestones, computed from the local donation history. Only a
        // signed-in donor has a profile to count; managers without one skip it.
        val card = findViewById<android.view.View>(R.id.cardMilestones)
        val tvMilestones = findViewById<TextView>(R.id.tvMilestones)
        val donorId = Session.donorId
        if (donorId != null) {
            val stats = DonationStats.from(db.donationsForDonor(donorId).map { it.date })
            card.visibility = android.view.View.VISIBLE
            val badges = if (stats.earnedMilestones.isEmpty()) "none yet"
            else stats.earnedMilestones.joinToString(", ") { ordinal(it) }
            val next = stats.nextMilestone?.let { "Next milestone: your ${ordinal(it)} donation." }
                ?: "You have earned every milestone — thank you!"
            tvMilestones.text = buildString {
                append("Total donations: ${stats.totalDonations}\n")
                append("Current streak: ${stats.streak}\n")
                append("You may have helped up to ${stats.mayHaveHelped} people\n")
                append("Badges earned: $badges\n")
                append(next)
            }
        } else {
            card.visibility = android.view.View.GONE
        }
    }

    /** 1 -> "1st", 3 -> "3rd", 11 -> "11th", 21 -> "21st". */
    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
