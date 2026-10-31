package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.ContactDetail
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.util.Background
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
            Background.run {
                val donorId = Session.donorId
                if (donorId != null) {
                    SupabaseSync.pullDonorDetail(db, donorId)
                } else {
                    SupabaseSync.pullAll(db)
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
            }
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
            Background.run {
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
            }
        }
    }

    /**
     * P2P: the "I'm available to donate" switch.
     *
     * Shown only while the donor is actually eligible — offering to help is
     * refused by the server unless this is on, and it would be dishonest to
     * offer a switch that cannot be acted on. When they are not eligible the
     * next-eligible date above already says why.
     */
    private fun setupAvailabilitySwitch(donor: Donor, eligible: Boolean) {
        val row = findViewById<View>(R.id.rowAvailability)
        val note = findViewById<TextView>(R.id.tvAvailabilityNote)
        val sw = findViewById<SwitchCompat>(R.id.swAvailable)
        if (!eligible) {
            row.visibility = View.GONE
            note.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        note.visibility = View.VISIBLE
        // Detach first: setting isChecked while a listener is attached would
        // fire it and immediately push a write we did not ask for.
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = donor.availableToDonate
        sw.setOnCheckedChangeListener { _, checked -> setAvailability(donor, checked, sw) }
    }

    private fun setAvailability(donor: Donor, available: Boolean, sw: SwitchCompat) {
        val updated = donor.copy(availableToDonate = available)
        // Upsert by id so the local donor row keeps its donations.
        db.insertDonorWithId(updated)

        if (!SupabaseSync.isOnline) {
            Toast.makeText(
                this,
                "Saved on this phone (not synced to Supabase).", Toast.LENGTH_SHORT
            ).show()
            return
        }
        Background.run {
            val ok = SupabaseSync.setAvailabilityRemote(donor.id, available)
            // Switching on also makes sure the donor's phone exists in
            // contact_details, so a requester who accepts their offer can
            // actually reach them. It stays hidden until then.
            val authId = updated.authId ?: Session.authUserId
            if (available && authId != null && donor.phone.isNotBlank()) {
                SupabaseSync.upsertContactDetailsRemote(authId, donor.phone)
                db.upsertContactDetails(ContactDetail(authId, donor.phone))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(
                        this,
                        "Saved on this phone, but the server refused the update.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                renderFromLocal()
            }
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
        tv.setTextColor(getColor(if (eligible) R.color.status_ok else R.color.status_warn))
        val next = BloodCompatibility.nextEligibleDate(donor)
        findViewById<TextView>(R.id.tvProfileNext).text =
            if (next == null) "Next eligible date: now — you can donate today."
            else "Next eligible date: $next (90 days after your last donation)"

        setupAvailabilitySwitch(donor, eligible)

        val history = db.donationsForDonor(donorId)
        val rv = findViewById<RecyclerView>(R.id.rvHistory)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DonationAdapter(history)
        findViewById<TextView>(R.id.tvNoHistory).visibility =
            if (history.isEmpty()) View.VISIBLE else View.GONE
    }
}
