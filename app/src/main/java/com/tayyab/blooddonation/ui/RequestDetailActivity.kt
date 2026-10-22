package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.BloodCompatibility

class RequestDetailActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private var requestId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_detail)
        title = "Request Detail"
        db = DbHelper(this)
        requestId = intent.getLongExtra("requestId", -1)

        findViewById<Button>(R.id.btnFulfill).setOnClickListener {
            val req = db.getRequest(requestId) ?: return@setOnClickListener
            if (req.status == "Fulfilled") {
                Toast.makeText(this, "Already fulfilled.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = db.fulfillRequest(req)
            Toast.makeText(
                this,
                if (ok) "Request fulfilled from stock." else "Not enough ${req.bloodGroup} units in stock.",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val req = db.getRequest(requestId) ?: run { finish(); return }
        findViewById<TextView>(R.id.tvReqDetailTitle).text =
            "${req.patientName}  (${req.bloodGroup})"
        findViewById<TextView>(R.id.tvReqDetailInfo).text =
            "Hospital: ${req.hospital}\n" +
            "Units needed: ${req.unitsNeeded}\n" +
            "Urgency: ${req.urgency}\n" +
            "Contact: ${req.contact}\n" +
            "Requested on: ${req.createdAt}\n" +
            "Status: ${req.status}\n" +
            "In stock (${req.bloodGroup}): ${db.stockLevels()[req.bloodGroup] ?: 0} unit(s)"

        val compatibleGroups = BloodCompatibility.donorGroupsFor(req.bloodGroup)
        findViewById<TextView>(R.id.tvCompatNote).text =
            "A ${req.bloodGroup} patient can receive from: ${compatibleGroups.joinToString(", ")}"

        val donors = db.getAllDonors().filter { d ->
            BloodCompatibility.isCompatible(d.bloodGroup, req.bloodGroup) &&
                BloodCompatibility.eligibility(d).first
        }
        val rv = findViewById<RecyclerView>(R.id.rvCompatDonors)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DonorAdapter(donors) { donor ->
            val i = Intent(this, DonorDetailActivity::class.java)
            i.putExtra("donorId", donor.id)
            startActivity(i)
        }
    }
}
