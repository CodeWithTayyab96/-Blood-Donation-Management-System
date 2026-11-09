package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.CertificatePdf
import com.tayyab.blooddonation.util.CertificateText
import com.tayyab.blooddonation.util.ContactActions
import com.tayyab.blooddonation.util.DonationReminderScheduler
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync

class DonorDetailActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private var donorId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donor_detail)
        title = "Donor Detail"
        db = DbHelper(this)
        donorId = intent.getLongExtra("donorId", -1)
        Session.load(this)
        // Only managers delete donors; a donor may only record their own donation.
        if (!Session.isManager) {
            findViewById<Button>(R.id.btnDeleteDonor).visibility = View.GONE
        }

        findViewById<Button>(R.id.btnRecordDonation).setOnClickListener {
            if (!Session.isManager && Session.donorId != donorId) {
                Toast.makeText(this, "You can only record your own donation", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val donor = db.getDonor(donorId) ?: return@setOnClickListener
            val (eligible, reason) = BloodCompatibility.eligibility(donor)
            if (!eligible) {
                Toast.makeText(this, "Not eligible: $reason", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (SupabaseSync.isOnline) {
                // Online first (donation row + donor date + stock), then
                // the same change locally. If the online step fails, the
                // local record still stands and the user is told so.
                val spinner = findViewById<ProgressBar>(R.id.progressSync)
                val label = findViewById<TextView>(R.id.syncLabel)
                spinner.visibility = View.VISIBLE
                label.visibility = View.VISIBLE
                label.text = "Recording donation…"
                Background.run {
                    val outcome = SupabaseSync.recordDonationRemote(donor, 1)
                    val recorded = outcome as? SupabaseSync.DonationOutcome.Recorded
                    if (recorded != null) {
                        // Keep the server id locally, so the pull that follows
                        // updates this row instead of adding a second one.
                        db.recordDonationWithId(donor, 1, recorded.donationId, recorded.movementId)
                    } else {
                        db.recordDonation(donor, 1)
                    }
                    // A recorded donation moves the 90-day eligible date, so
                    // replace the pending one-time reminder (local-only work).
                    if (Session.donorId == donorId) {
                        DonationReminderScheduler.rescheduleForDonor(
                            this@DonorDetailActivity, donorId
                        )
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        spinner.visibility = View.GONE
                        label.visibility = View.GONE
                        val message = when (outcome) {
                            is SupabaseSync.DonationOutcome.Recorded ->
                                if (outcome.stockSynced) {
                                    "Thank you for donating! 1 unit of ${donor.bloodGroup} added to stock."
                                } else {
                                    // The donation row landed but the stock
                                    // call did not. Saying "recorded" without
                                    // this caveat is how the two stock counts
                                    // silently drift apart.
                                    "Thank you for donating! The donation was recorded, but the " +
                                        "stock count did not update on the server\n" +
                                        "${outcome.stockDetail}\nPlease tell the manager."
                                }
                            is SupabaseSync.DonationOutcome.Rejected ->
                                // The old message said "saved on this phone",
                                // which hid the real cause. Recording a
                                // donation is manager-only in the database now,
                                // so a donor who tries it gets a policy refusal
                                // — and they need to see that, not a shrug.
                                "The donation was NOT recorded on the server.\n" +
                                    "${outcome.failure.stage}: ${outcome.failure.detail}\n" +
                                    "It is saved on this phone only."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
            } else {
                db.recordDonation(donor, 1)
                if (Session.donorId == donorId) {
                    DonationReminderScheduler.rescheduleForDonor(this, donorId)
                }
                Toast.makeText(this, "Thank you for donating! 1 unit of ${donor.bloodGroup} added to stock.", Toast.LENGTH_LONG).show()
                refresh()
            }
        }

        findViewById<Button>(R.id.btnCertificate).setOnClickListener { generateCertificate() }

        findViewById<Button>(R.id.btnDeleteDonor).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete donor")
                .setMessage("Remove this donor permanently?")
                .setPositiveButton("Delete") { _, _ ->
                    if (SupabaseSync.isOnline) {
                        val spinner = findViewById<ProgressBar>(R.id.progressSync)
                        val label = findViewById<TextView>(R.id.syncLabel)
                        spinner.visibility = View.VISIBLE
                        label.visibility = View.VISIBLE
                        label.text = "Deleting donor…"
                        Background.run {
                            SupabaseSync.deleteDonorRemote(donorId)
                            db.deleteDonor(donorId)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                spinner.visibility = View.GONE
                                label.visibility = View.GONE
                                finish()
                            }
                        }
                    } else {
                        db.deleteDonor(donorId)
                        finish()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Phase 2: a PDF certificate for the donor's most recent recorded
     * donation. File work runs on [Background], not the UI thread; a failure
     * is logged by the renderer and shown to the user here.
     */
    private fun generateCertificate() {
        val donor = db.getDonor(donorId) ?: return
        val latest = db.donationsForDonor(donorId).firstOrNull()?.date
        if (latest == null) {
            Toast.makeText(this, R.string.certificate_no_donation, Toast.LENGTH_SHORT).show()
            return
        }
        val lines = CertificateText.lines(
            title = getString(R.string.certificate_title),
            nameLabel = getString(R.string.certificate_name_label), name = donor.name,
            groupLabel = getString(R.string.certificate_group_label), group = donor.bloodGroup,
            dateLabel = getString(R.string.certificate_date_label), date = latest,
            thanks = getString(R.string.certificate_thanks)
        )
        Background.run {
            val file = CertificatePdf.create(applicationContext, lines)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    Toast.makeText(this, R.string.certificate_failed, Toast.LENGTH_LONG).show()
                } else {
                    CertificatePdf.share(this, file)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            val spinner = findViewById<ProgressBar>(R.id.progressSync)
            val label = findViewById<TextView>(R.id.syncLabel)
            spinner.visibility = View.VISIBLE
            label.visibility = View.VISIBLE
            label.text = "Updating donor…"
            Background.run {
                SupabaseSync.pullDonorDetail(db, donorId)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        renderFromLocal()
                        spinner.visibility = View.GONE
                        label.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun renderFromLocal() {
        Background.run {
            val donor = db.getDonor(donorId)
            val eligibility = donor?.let { BloodCompatibility.eligibility(it) }
            val verified = db.isDonorVerified(donorId)
            val history = db.donationsForDonor(donorId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (donor == null) { finish(); return@runOnUiThread }
                val (eligible, reason) = eligibility!!
                findViewById<TextView>(R.id.tvDetailName).text = "${donor.name}  (${donor.bloodGroup})"
                findViewById<TextView>(R.id.tvDetailInfo).text =
                    "Age: ${donor.age} years\n" +
                    "Gender: ${donor.gender}\n" +
                    "Weight: ${donor.weightKg} kg\n" +
                    "Phone: ${donor.phone.ifBlank { "hidden" }}\n" +
                    "City: ${donor.city}\n" +
                    "Last donation: ${donor.lastDonationDate ?: "Never"}"
                val tv = findViewById<TextView>(R.id.tvDetailEligibility)
                tv.text = (if (eligible) "✓ " else "✗ ") + reason
                tv.setTextColor(getColor(if (eligible) R.color.status_ok else R.color.status_warn))
                tv.setBackgroundResource(
                    if (eligible) R.drawable.bg_pill_green else R.drawable.bg_pill_amber
                )

                val tvVerified = findViewById<TextView>(R.id.tvVerified)
                tvVerified.visibility = View.VISIBLE
                if (verified) {
                    tvVerified.text = getString(R.string.verify_badge)
                    tvVerified.setTextColor(getColor(R.color.status_ok))
                    tvVerified.setBackgroundResource(R.drawable.bg_pill_green)
                } else {
                    tvVerified.text = getString(R.string.verify_not_verified)
                    tvVerified.setTextColor(getColor(R.color.text_secondary))
                    tvVerified.setBackgroundResource(R.drawable.bg_pill_grey)
                }
                val btnVerify = findViewById<Button>(R.id.btnVerify)
                if (Session.isManager) {
                    btnVerify.visibility = View.VISIBLE
                    btnVerify.setOnClickListener {
                        startActivity(
                            Intent(this, VerifyDonorActivity::class.java).putExtra("donorId", donorId)
                        )
                    }
                } else {
                    btnVerify.visibility = View.GONE
                }

                ContactActions.bind(
                    this,
                    findViewById<View>(R.id.contactActions),
                    findViewById<Button>(R.id.btnCall),
                    findViewById<Button>(R.id.btnWhatsApp),
                    donor.phone
                )

                findViewById<Button>(R.id.btnCertificate).visibility =
                    if (history.isEmpty()) View.GONE else View.VISIBLE
                val rv = findViewById<RecyclerView>(R.id.rvHistory)
                rv.layoutManager = LinearLayoutManager(this)
                val donationAdapter = DonationAdapter()
                rv.adapter = donationAdapter
                donationAdapter.submitList(history)
                findViewById<TextView>(R.id.tvNoHistory).visibility =
                    if (history.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
