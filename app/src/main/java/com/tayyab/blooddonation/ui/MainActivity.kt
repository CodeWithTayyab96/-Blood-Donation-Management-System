package com.tayyab.blooddonation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.BackoffPolicy
import com.google.android.material.card.MaterialCardView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tayyab.blooddonation.BuildConfig
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.AppUpdater
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.AiCoach
import com.tayyab.blooddonation.util.AiRules
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.DonationReminderScheduler
import com.tayyab.blooddonation.util.RequestAlertScheduler
import com.tayyab.blooddonation.util.RequestAlertWorker
import com.tayyab.blooddonation.util.RequestAlerts
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync
import com.tayyab.blooddonation.util.UpdateNotifier
import com.tayyab.blooddonation.util.UpdatePrompt
import com.tayyab.blooddonation.util.UpdateWorker
import com.tayyab.blooddonation.util.MotivationFacts
import com.tayyab.blooddonation.util.DonationStats
import com.tayyab.blooddonation.util.FcmPush
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001

        /** Phase 4 AI card cache file, and the three keys in it. */
        const val AI_PREFS = "ai_coach"
        const val KEY_AI_MSG_DAY = "message_day"
        const val KEY_AI_MSG_TEXT = "message_text"
        const val KEY_AI_FAIL_DAY = "fail_day"
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
        // This is the manager dashboard. An ordinary user has their own home,
        // decided from the JWT role — never from the local users row.
        if (!Session.isManager) {
            startActivity(Intent(this, UserHomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        db = DbHelper(this)

        // Bottom navigation (v1.22): Home is this dashboard; Donors, Stock
        // and Requests are tabs now, and the chatbot is the floating button.
        BottomNav.setup(this, R.id.nav_home)
        BottomNav.setupChatFab(this)

        // Phase 1 updates: keep the 12-hour background check scheduled, then
        // decide whether this launch should check now (see handleUpdatePrompt).
        UpdateWorker.schedule(this)
        handleUpdatePrompt()

        // Phase 3 push: make sure this device's FCM token row exists for the
        // signed-in user, in case Firebase rotated it since the last launch
        // (or this build only just got Firebase values). No-op — and cheap —
        // when push is not configured, when there was never a session, or for
        // local-only accounts that no push can reach anyway. Runs in the
        // background: it needs network I/O.
        val appContext = applicationContext
        Background.run { FcmPush.refreshToken(appContext) }

        // Phase 4 AI card: fetch today's personalised line at most once per
        // calendar day, behind the same privacy rules as everything else;
        // failures mark the day skipped so an unconfigured server is not
        // pinged on every dashboard open.
        refreshAiCardIfNeeded()

        // Donors get the ~15-minute request poll; managers do not. Any poll left
        // behind by a previous donor session is stopped here.
        if (Session.role == Session.ROLE_DONOR && Session.donorId != null) {
            RequestAlertScheduler.schedule(this)
            askForNotificationPermission()
            DonationReminderScheduler.rescheduleForDonor(this, Session.donorId)
        } else {
            // Only the request poll is cancelled here: this is the manager
            // dashboard. The eligibility reminder is cancelled on logout and
            // account deletion, not when a manager opens their own home.
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
        // Phase 3: camps, slots and forecast entry points. Donors reach the
        // same screens from their home; managers get them here too, plus the
        // manager-only create actions inside each screen.
        findViewById<Button>(R.id.btnCamps).setOnClickListener {
            startActivity(Intent(this, CampListActivity::class.java))
        }
        findViewById<Button>(R.id.btnSlots).setOnClickListener {
            startActivity(Intent(this, SlotListActivity::class.java))
        }
        findViewById<Button>(R.id.btnForecast).setOnClickListener {
            startActivity(Intent(this, ForecastActivity::class.java))
        }
        // Urgent banner: tap opens the requests list.
        findViewById<MaterialCardView>(R.id.cardUrgentBanner).setOnClickListener {
            startActivity(Intent(this, RequestListActivity::class.java))
        }
        findViewById<Button>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        // Phase 2: monthly CSV export is manager-only (the activity checks the
        // JWT role too, so hiding the button is never the only guard).
        findViewById<Button>(R.id.btnManagerReport).setOnClickListener {
            startActivity(Intent(this, ManagerReportActivity::class.java))
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
    }

    /** Three-dot menu in the app bar: the secondary destinations. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean =
        BottomNav.onCreateOptionsMenu(this, menu)

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        BottomNav.onPrepareOptionsMenu(this, menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        BottomNav.onOptionsItemSelected(this, item) || super.onOptionsItemSelected(item)

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
            .setMessage(getString(R.string.notification_permission_message))
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

    /**
     * The updater's entry point on launch.
     *
     *  - an update the background worker found is offered straight away;
     *  - a tap on the "version available" notification forces a check;
     *  - otherwise a check runs at most once every 12 hours.
     *
     * It never announces "you are up to date" on its own — that only happens
     * when the user asks for it from the dashboard.
     */
    private fun handleUpdatePrompt() {
        val fromNotification =
            intent?.getBooleanExtra(UpdateNotifier.EXTRA_SHOW_UPDATE_PROMPT, false) == true
        intent?.removeExtra(UpdateNotifier.EXTRA_SHOW_UPDATE_PROMPT)
        UpdatePrompt.checkOnLaunch(this, fromNotification)
    }

    private fun checkForUpdate() {
        val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
        val label = findViewById<android.widget.TextView>(R.id.syncLabel)
        spinner.visibility = android.view.View.VISIBLE
        label.visibility = android.view.View.VISIBLE
        label.text = "Checking for updates…"
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
            runOnUiThread {
                spinner.visibility = android.view.View.GONE
                label.visibility = android.view.View.GONE
            }
        }
    }

    private fun showUpdateDialog(info: AppUpdater.UpdateInfo) {
        UpdatePrompt.show(this, info)
    }

    private fun startUpdateDownload(info: AppUpdater.UpdateInfo) {
        UpdatePrompt.startDownload(this, info)
    }

    private fun refresh() {
        // Show the local copy immediately, then quietly refresh it from
        // Supabase in the background and re-render. If Supabase is not
        // configured or unreachable, the local numbers simply stay — the
        // dashboard never waits on the network and never crashes.
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
            val label = findViewById<android.widget.TextView>(R.id.syncLabel)
            spinner.visibility = android.view.View.VISIBLE
            label.visibility = android.view.View.VISIBLE
            label.text = "Updating dashboard…"
            Background.run {
                SupabaseSync.pullAll(db)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        renderFromLocal()
                        spinner.visibility = android.view.View.GONE
                        label.visibility = android.view.View.GONE
                    }
                }
            }
        }
    }

    private fun refreshAiCardIfNeeded() {
        val prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE)
        val today = LocalDate.now().toEpochDay()
        val messageCached = prefs.contains(KEY_AI_MSG_TEXT) &&
            prefs.getLong(KEY_AI_MSG_DAY, -1L) == today
        val cachedDay = prefs.getLong(KEY_AI_MSG_DAY, -1L).takeIf { it >= 0 }
        if (!AiRules.shouldFetch(today, cachedDay, prefs.getLong(KEY_AI_FAIL_DAY, -1L).takeIf { it >= 0 }, messageCached)) {
            return
        }
        // The quote comes from the SHARED daily bank on the server, so no
        // personal data is sent and no donor profile is needed — any signed-in
        // user can be encouraged. One call per day for the whole project, not
        // one per user: free models only allow about 50 calls a day.
        if (Session.accessToken.isNullOrBlank()) return
        Background.run {
            val message = AiCoach.quote()
            prefs.edit()
                .apply {
                    if (message != null) {
                        putString(KEY_AI_MSG_TEXT, message)
                        putLong(KEY_AI_MSG_DAY, today)
                    } else {
                        // No quote available (offline, not configured, daily
                        // cap, or nothing usable generated). The card keeps
                        // its local fact of the day — never an error, never a
                        // blank card.
                        putLong(KEY_AI_FAIL_DAY, today)
                    }
                }
                .apply()
            runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
        }
    }

    private fun renderFromLocal() {
        Background.run {
            val donorCount = db.donorCount()
            val totalUnits = db.totalUnitsInStock()
            val pending = db.pendingRequestCount()
            val donationsMonth = db.donationsThisMonth()
            val stock = db.stockLevels()
            val donorId = Session.donorId
            val stats = donorId?.let {
                DonationStats.from(db.donationsForDonor(it).map { d -> d.date })
            }
            val prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE)
            val aiText = prefs.getString(KEY_AI_MSG_TEXT, null)
            val today = LocalDate.now().toEpochDay()
            val aiDay = prefs.getLong(KEY_AI_MSG_DAY, -1L)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.tvStatDonors).text = donorCount.toString()
                findViewById<TextView>(R.id.tvStatUnits).text = totalUnits.toString()
                findViewById<TextView>(R.id.tvStatRequests).text = pending.toString()
                findViewById<TextView>(R.id.tvStatMonth).text = donationsMonth.toString()
                val banner = findViewById<MaterialCardView>(R.id.cardUrgentBanner)
                banner.visibility = if (pending > 0) View.VISIBLE else View.GONE
                if (pending > 0) {
                    findViewById<TextView>(R.id.tvUrgentBanner).text =
                        if (pending == 1) "Urgent: 1 pending blood request"
                        else "Urgent: $pending pending blood requests"
                }
                findViewById<TextView>(R.id.tvStockSummary).text =
                    BloodCompatibility.ALL_GROUPS.joinToString("\n") { g -> "$g : ${stock[g] ?: 0} unit(s)" }

                findViewById<TextView>(R.id.tvFact).text =
                    if (aiText != null && aiDay == today) aiText
                    else MotivationFacts.factFor(today)

                val card = findViewById<android.view.View>(R.id.cardMilestones)
                val tvMilestones = findViewById<TextView>(R.id.tvMilestones)
                if (donorId != null && stats != null) {
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
                BottomNav.updateBadges(this@MainActivity)
            }
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
