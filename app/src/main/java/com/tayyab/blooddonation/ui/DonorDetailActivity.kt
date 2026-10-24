package com.tayyab.blooddonation.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session

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
            db.recordDonation(donor, 1)
            Toast.makeText(this, "Donation recorded. 1 unit of ${donor.bloodGroup} added to stock.", Toast.LENGTH_LONG).show()
            refresh()
        }

        findViewById<Button>(R.id.btnDeleteDonor).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete donor")
                .setMessage("Remove this donor permanently?")
                .setPositiveButton("Delete") { _, _ ->
                    db.deleteDonor(donorId)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val donor = db.getDonor(donorId) ?: run { finish(); return }
        findViewById<TextView>(R.id.tvDetailName).text = "${donor.name}  (${donor.bloodGroup})"
        findViewById<TextView>(R.id.tvDetailInfo).text =
            "Age: ${donor.age} years\n" +
            "Gender: ${donor.gender}\n" +
            "Weight: ${donor.weightKg} kg\n" +
            "Phone: ${donor.phone}\n" +
            "City: ${donor.city}\n" +
            "Last donation: ${donor.lastDonationDate ?: "Never"}"
        val (eligible, reason) = BloodCompatibility.eligibility(donor)
        val tv = findViewById<TextView>(R.id.tvDetailEligibility)
        tv.text = (if (eligible) "✓ " else "✗ ") + reason
        tv.setTextColor(Color.parseColor(if (eligible) "#2E7D32" else "#E65100"))

        val history = db.donationsForDonor(donorId)
        val rv = findViewById<RecyclerView>(R.id.rvHistory)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DonationAdapter(history)
        findViewById<TextView>(R.id.tvNoHistory).visibility =
            if (history.isEmpty()) View.VISIBLE else View.GONE
    }
}
