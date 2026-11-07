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
import androidx.appcompat.widget.SwitchCompat
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.ContactDetail
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.util.BadgeUi
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.DonationReminderScheduler
import com.tayyab.blooddonation.util.DonationStats
import com.tayyab.blooddonation.util.FcmPush
import com.tayyab.blooddonation.util.MotivationFacts
import com.tayyab.blooddonation.util.RequestAlertScheduler
import com.tayyab.blooddonation.util.RequestAlerts
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseClient
import com.tayyab.blooddonation.util.SupabaseSync
import com.tayyab.blooddonation.util.UpdateNotifier
import com.tayyab.blooddonation.util.UpdatePrompt
import com.tayyab.blooddonation.util.UpdateWorker
import java.time.LocalDate

/**
 * Home for an ordinary signed-in user.
 *
 * Which screen this is depends on the JWT's `app_metadata.role`, not on the
 * local `users` row: a manager goes to [MainActivity], everyone else lands
 * here. The two screens are deliberately different — a donor sees the things a
 * donor does (ask for blood, offer to help, their own requests), not the blood
 * bank's dashboard.
 *
 * Every write on this screen needs the server. Cached data is still readable
 * offline; a write without a connection says so instead of pretending.
 */
class UserHomeActivity : AppCompatActivity() {

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
        // A manager has a different home; never show them this one.
        if (Session.isManager) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_user_home)
        db = DbHelper(this)

        UpdateWorker.schedule(this)
        UpdatePrompt.checkOnLaunch(
            this,
            intent?.getBooleanExtra(UpdateNotifier.EXTRA_SHOW_UPDATE_PROMPT, false) == true
        )
        intent?.removeExtra(UpdateNotifier.EXTRA_SHOW_UPDATE_PROMPT)

        // Donor-only setup. MainActivity is the manager dashboard and returns
        // early for donors, so the poll, the permission prompt and the FCM
        // token upload must live here: this is the screen a donor opens.
        // refreshToken is a no-op when Firebase is not configured.
        Background.run { FcmPush.refreshToken(applicationContext) }
        if (Session.donorId != null) {
            RequestAlertScheduler.schedule(this)
            askForNotificationPermission()
            // Recompute the one-off 90-day reminder from the local donation
            // history, so a reinstall or a missed day cannot leave a stale job.
            DonationReminderScheduler.rescheduleForDonor(this, Session.donorId)
        }

        findViewById<TextView>(R.id.tvUserHomeWho).text = Session.label()
        findViewById<TextView>(R.id.tvUserFact).text =
            MotivationFacts.factFor(LocalDate.now().toEpochDay())

        findViewById<Button>(R.id.btnNeedBlood).setOnClickListener {
            startActivity(Intent(this, CreateRequestActivity::class.java))
        }
        // Phase 3: camps, slots, forecast and offline emergency are available to
        // donors too (managers additionally get the create actions inside them).
        findViewById<Button>(R.id.btnCamps).setOnClickListener {
            startActivity(Intent(this, CampListActivity::class.java))
        }
        findViewById<Button>(R.id.btnSlots).setOnClickListener {
            startActivity(Intent(this, SlotListActivity::class.java))
        }
        findViewById<Button>(R.id.btnForecast).setOnClickListener {
            startActivity(Intent(this, ForecastActivity::class.java))
        }
        findViewById<Button>(R.id.btnEmergency).setOnClickListener {
            startActivity(Intent(this, EmergencyActivity::class.java))
        }
        findViewById<Button>(R.id.btnMyRequests).setOnClickListener {
            openRequests(RequestListActivity.MODE_MINE)
        }
        findViewById<Button>(R.id.btnBrowseRequests).setOnClickListener {
            openRequests(RequestListActivity.MODE_BROWSE)
        }
        findViewById<Button>(R.id.btnAskAi).setOnClickListener {
            startActivity(Intent(this, AskDonateActivity::class.java))
        }
        findViewById<Button>(R.id.btnUserProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<Button>(R.id.btnUserSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnUserLogout).setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        renderAvailability()
        showBadges()
    }

    /** Phase 2: milestones from the local donation history. */
    private fun showBadges() {
        val tv = findViewById<TextView>(R.id.tvBadges)
        val donorId = Session.donorId
        if (donorId == null) {
            tv.visibility = View.GONE
            return
        }
        val stats = DonationStats.from(db.donationsForDonor(donorId).map { it.date })
        tv.visibility = View.VISIBLE
        tv.text = BadgeUi.text(this, stats.earnedBadges)
    }

    /**
     * One-time prompt for the notification permission (Android 13+), so the
     * urgent-request poll can actually alert the donor. Asked once behind a
     * short explanation; if they decline, the flag in [RequestAlerts] stops
     * the prompt coming back on every launch.
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

    private fun openRequests(mode: String) {
        startActivity(
            Intent(this, RequestListActivity::class.java)
                .putExtra(RequestListActivity.EXTRA_MODE, mode)
        )
    }

    /**
     * The availability switch, with the next eligible date underneath.
     *
     * Hidden entirely for a requester who has no donor profile — offering to
     * donate is not something they can do until they have one, and a switch
     * that cannot work is worse than no switch.
     */
    private fun renderAvailability() {
        val card = findViewById<View>(R.id.cardAvailability)
        val sw = findViewById<SwitchCompat>(R.id.swUserAvailable)
        val info = findViewById<TextView>(R.id.tvUserEligibility)

        val donor = Session.donorId?.let { db.getDonor(it) }
        if (donor == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE

        // Detach first: setting isChecked while a listener is attached would
        // fire it and push a write nobody asked for.
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = donor.availableToDonate
        sw.setOnCheckedChangeListener { _, checked -> setAvailability(donor, checked, sw) }

        val (eligible, reason) = BloodCompatibility.eligibility(donor)
        info.text = if (eligible) "✓ $reason" else "✗ $reason"
        info.setTextColor(getColor(if (eligible) R.color.status_ok else R.color.status_warn))
    }

    private fun setAvailability(donor: Donor, available: Boolean, sw: SwitchCompat) {
        if (!SupabaseSync.isOnline) {
            // Online-only: revert the switch rather than save a half-state that
            // the server does not know about.
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = donor.availableToDonate
            sw.setOnCheckedChangeListener { _, checked -> setAvailability(donor, checked, sw) }
            Toast.makeText(
                this,
                "Changing your availability needs an internet connection.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val updated = donor.copy(availableToDonate = available)
        Background.run {
            val ok = SupabaseSync.setAvailabilityRemote(donor.id, available)
            if (ok) {
                db.insertDonorWithId(updated)
                // Switching on also makes sure the phone number exists in
                // contact_details, so an accepted offer can reach them. It
                // stays hidden until then.
                val authId = updated.authId ?: Session.authUserId
                if (available && authId != null && donor.phone.isNotBlank()) {
                    SupabaseSync.upsertContactDetailsRemote(authId, donor.phone)
                    db.upsertContactDetails(ContactDetail(authId, donor.phone))
                }
            }
            val error = SupabaseClient.lastError
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(
                        this,
                        "The server refused the change" +
                            (error?.let { ": $it" } ?: "."),
                        Toast.LENGTH_LONG
                    ).show()
                }
                renderAvailability()
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("Your data stays on this phone; you can sign in again with your email.")
            .setPositiveButton("Log out") { _, _ ->
                // Stop the request poll and drop this device's push token row
                // BEFORE the session is cleared: clearing it loses the sign-in
                // context the token delete needs (the row is keyed by Auth
                // user id).
                RequestAlertScheduler.cancel(this)
                // A signed-out phone must not wake up for somebody else's
                // eligibility reminder.
                DonationReminderScheduler.cancel(this)
                Background.run { FcmPush.forgetToken(applicationContext) }
                Session.logout(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
