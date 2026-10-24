package com.tayyab.blooddonation.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session

class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_profile)
        title = "My Profile"
        val db = DbHelper(this)

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
