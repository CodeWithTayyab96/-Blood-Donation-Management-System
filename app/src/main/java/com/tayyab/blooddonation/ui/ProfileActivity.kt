package com.tayyab.blooddonation.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync

class ProfileActivity : AppCompatActivity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_profile)
        title = "My Profile"
        db = DbHelper(this)

        // Local profile first, then a quiet online refresh (unreachable
        // Supabase = local profile stays, same rule as everywhere else).
        renderFromLocal()
        setupSyncRetry()
        if (SupabaseSync.isOnline) {
            Thread {
                val donorId = Session.donorId
                if (donorId != null) {
                    SupabaseSync.pullDonorDetail(db, donorId)
                } else {
                    SupabaseSync.pullAll(db)
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
            }.start()
        }
    }

    /**
     * The retry for a donor profile that failed to sync at
     * registration (see RegisterActivity): shown only to a signed-in
     * donor with an Auth session whose profile is still marked
     * pending. On success the local row is re-linked to the
     * server-assigned id (account row and any donations recorded
     * meanwhile move with it) and the flag is cleared; on failure
     * the button stays, and nothing is claimed.
     */
    private fun setupSyncRetry() {
        val btn = findViewById<Button>(R.id.btnSyncProfile)
        val show = Session.donorId != null &&
            Session.authUserId != null &&
            Session.profilePendingSync
        btn.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        btn.setOnClickListener {
            btn.isEnabled = false
            Thread {
                val donorId = Session.donorId
                val authId = Session.authUserId
                val donor = donorId?.let { db.getDonor(it) }
                val remoteId = if (donor != null && authId != null) {
                    SupabaseSync.addDonorRemote(donor, authId)
                } else {
                    null
                }
                runOnUiThread {
                    btn.isEnabled = true
                    if (remoteId != null && donor != null) {
                        val newId = db.relinkDonorProfile(donor.id, donor.copy(id = remoteId))
                        val access = Session.accessToken
                        val refresh = Session.refreshToken
                        if (!access.isNullOrBlank() && !refresh.isNullOrBlank()) {
                            Session.saveAuth(
                                this, Session.username ?: "", Session.ROLE_DONOR, newId,
                                Session.email ?: "", Session.authUserId, access, refresh
                            )
                        } else {
                            // Tokens are gone (dead session): keep the
                            // local login, just move it to the new id.
                            Session.save(this, Session.username ?: "", Session.ROLE_DONOR, newId)
                        }
                        Session.setProfilePendingSync(this, false)
                        btn.visibility = View.GONE
                        renderFromLocal()
                        Toast.makeText(this, "Profile synced to Supabase", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this, "Profile sync failed — check your connection and try again",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }

    private fun renderFromLocal() {
        val donorId = Session.donorId
        if (donorId == null) {
            // Manager account: no donor profile, show account + overall counts.
            findViewById<TextView>(R.id.tvProfileName).text = Session.username ?: "Manager"
            findViewById<TextView>(R.id.tvProfileInfo).text =
                "Role: Manager (full access)\n" +
                "Registered donors: ${db.donorCount()}\n" +
                "Units in stock: ${db.totalUnitsInStock()}\n" +
                "Pending requests: ${db.pendingRequestCount()}"
            findViewById<TextView>(R.id.tvProfileEligibility).text =
                "Managers manage donors, stock and requests from the dashboard."
            findViewById<TextView>(R.id.tvProfileNext).visibility = View.GONE
            findViewById<TextView>(R.id.tvNoHistory).visibility = View.GONE
            return
        }

        val donor = db.getDonor(donorId) ?: run { finish(); return }
        findViewById<TextView>(R.id.tvProfileName).text = "${donor.name}  (${donor.bloodGroup})"
        findViewById<TextView>(R.id.tvProfileInfo).text =
            "Age: ${donor.age} years\n" +
            "Gender: ${donor.gender}\n" +
            "Weight: ${donor.weightKg} kg\n" +
            "Phone: ${donor.phone}\n" +
            "City: ${donor.city}\n" +
            "Last donation: ${donor.lastDonationDate ?: "Never"}"
        val (eligible, reason) = BloodCompatibility.eligibility(donor)
        val tv = findViewById<TextView>(R.id.tvProfileEligibility)
        tv.text = (if (eligible) "✓ " else "✗ ") + reason
        tv.setTextColor(Color.parseColor(if (eligible) "#2E7D32" else "#E65100"))
        val next = BloodCompatibility.nextEligibleDate(donor)
        findViewById<TextView>(R.id.tvProfileNext).text =
            if (next == null) "Next eligible date: now — you can donate today."
            else "Next eligible date: $next (90 days after your last donation)"

        val history = db.donationsForDonor(donorId)
        val rv = findViewById<RecyclerView>(R.id.rvHistory)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DonationAdapter(history)
        findViewById<TextView>(R.id.tvNoHistory).visibility =
            if (history.isEmpty()) View.VISIBLE else View.GONE
    }
}
