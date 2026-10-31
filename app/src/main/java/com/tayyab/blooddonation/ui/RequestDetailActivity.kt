package com.tayyab.blooddonation.ui

import android.content.Intent
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
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync

class RequestDetailActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private var requestId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_detail)
        title = "Request Detail"
        db = DbHelper(this)
        requestId = intent.getLongExtra("requestId", -1)
        Session.load(this)
        // Fulfilling a request moves stock, which is a manager action. Hiding
        // the button is a UI guard only — supabase_role_policies.sql is what
        // actually enforces it on the server.
        if (!Session.isManager) {
            findViewById<Button>(R.id.btnFulfill).visibility = View.GONE
        }

        findViewById<Button>(R.id.btnFulfill).setOnClickListener {
            val req = db.getRequest(requestId) ?: return@setOnClickListener
            if (req.status == "Fulfilled") {
                Toast.makeText(this, "Already fulfilled.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (SupabaseSync.isOnline) {
                // Online stock is the source of truth when reachable:
                // a short online stock means "not enough", full stop.
                // An online error falls back to the old local behaviour.
                Background.run {
                    when (val result = SupabaseSync.fulfilRequestRemote(req)) {
                        is SupabaseSync.FulfilResult.Success -> {
                            db.fulfillRequest(req)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(this, "Request fulfilled from stock.", Toast.LENGTH_LONG).show()
                                refresh()
                            }
                        }
                        is SupabaseSync.FulfilResult.Insufficient -> runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            // Name the server's number: the count on screen is
                            // the local copy, and the two can disagree.
                            Toast.makeText(
                                this,
                                "Not enough ${req.bloodGroup} units — the server has " +
                                    "${result.available}. Refreshing from the server.",
                                Toast.LENGTH_LONG
                            ).show()
                            refresh()
                        }
                        is SupabaseSync.FulfilResult.Error -> {
                            val ok = db.fulfillRequest(req)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(
                                    this,
                                    if (ok) "Request fulfilled on this phone (not synced to Supabase)."
                                    else "Not enough ${req.bloodGroup} units in stock.",
                                    Toast.LENGTH_LONG
                                ).show()
                                refresh()
                            }
                        }
                    }
                }
            } else {
                val ok = db.fulfillRequest(req)
                Toast.makeText(
                    this,
                    if (ok) "Request fulfilled from stock." else "Not enough ${req.bloodGroup} units in stock.",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
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
            Background.run {
                SupabaseSync.pullRequestDetail(db, requestId)
                runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
            }
        }
    }

    /**
     * A request created on this phone stores `createdAt` as a plain date
     * ("2026-10-31"); the same request pulled from Supabase comes back as a
     * full timestamptz. Showing only the date part keeps the screen identical
     * before and after a sync.
     */
    private fun displayDate(raw: String): String =
        if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') raw.substring(0, 10) else raw

    private fun renderFromLocal() {
        val req = db.getRequest(requestId) ?: run { finish(); return }
        findViewById<TextView>(R.id.tvReqDetailTitle).text =
            "${req.patientName}  (${req.bloodGroup})"
        findViewById<TextView>(R.id.tvReqDetailInfo).text =
            "Hospital: ${req.hospital}\n" +
            "Units needed: ${req.unitsNeeded}\n" +
            "Urgency: ${req.urgency}\n" +
            "Contact: ${req.contact}\n" +
            "Requested on: ${displayDate(req.createdAt)}\n" +
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
