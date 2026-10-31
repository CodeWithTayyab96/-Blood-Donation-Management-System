package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync
import java.time.LocalDate

/**
 * P2P: the manager's moderation screen — every report and every offer.
 *
 * This is the only place a manager acts on peer activity: cancelling a
 * request that was reported, or seeing which donors offered on what. It is
 * manager-only in the UI, and `supabase_p2p_policies.sql` is what actually
 * refuses the same reads for everyone else.
 */
class ModerationActivity : AppCompatActivity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        // Same pattern as AddRequestActivity: the UI guard only.
        if (!Session.isManager) {
            Toast.makeText(this, "Managers only", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_moderation)
        title = "Moderation"
        db = DbHelper(this)
    }

    override fun onResume() {
        super.onResume()
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            Background.run {
                SupabaseSync.pullReports(db)
                SupabaseSync.pullAllOffers(db)
                runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
            }
        }
    }

    private fun renderFromLocal() {
        renderReports()
        renderOffers()
    }

    private fun renderReports() {
        val container = findViewById<LinearLayout>(R.id.reportsContainer)
        container.removeAllViews()
        val reports = db.getReports()
        findViewById<TextView>(R.id.tvNoReports).visibility =
            if (reports.isEmpty()) View.VISIBLE else View.GONE

        for (r in reports) {
            val tv = TextView(this)
            tv.textSize = 14f
            val resolved = if (r.resolvedAt == null) "" else " (resolved)"
            tv.text = "Request #${r.requestId} — ${r.reason}$resolved"
            tv.setPadding(0, 8, 0, 2)
            container.addView(tv)

            if (r.resolvedAt == null) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val cancel = Button(this)
                cancel.text = "Cancel request"
                cancel.setOnClickListener { confirmCancelRequest(r.requestId, r.id) }
                val dismiss = Button(this)
                dismiss.text = "Mark resolved"
                dismiss.setOnClickListener { resolveReport(r.id) }
                row.addView(cancel)
                row.addView(dismiss)
                container.addView(row)
            }
        }
    }

    private fun renderOffers() {
        val container = findViewById<LinearLayout>(R.id.offersContainer)
        container.removeAllViews()
        val offers = db.getAllOffers()
        findViewById<TextView>(R.id.tvNoOffers).visibility =
            if (offers.isEmpty()) View.VISIBLE else View.GONE

        for (o in offers) {
            val tv = TextView(this)
            tv.textSize = 14f
            tv.text = "Request #${o.requestId} — ${o.donorName} (${o.donorGroup}, " +
                "${o.donorCity}) — ${o.status}"
            tv.setPadding(0, 8, 0, 2)
            container.addView(tv)
        }
    }

    /** Cancelling someone else's request is the heavy action here, so it asks
     *  first and says exactly what happens. */
    private fun confirmCancelRequest(requestId: Long, reportId: Long) {
        AlertDialog.Builder(this)
            .setTitle("Cancel request #$requestId?")
            .setMessage(
                "This closes the request for the person who posted it and for " +
                    "every donor who offered to help."
            )
            .setPositiveButton("Cancel request") { _, _ ->
                db.setRequestStatus(requestId, "Cancelled")
                db.markReportResolved(reportId, LocalDate.now().toString())
                renderFromLocal()
                if (SupabaseSync.isOnline) {
                    Background.run { SupabaseSync.cancelRequestRemote(requestId) }
                }
                Toast.makeText(this, "Request #$requestId cancelled", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun resolveReport(reportId: Long) {
        db.markReportResolved(reportId, LocalDate.now().toString())
        renderFromLocal()
        Toast.makeText(this, "Report marked resolved", Toast.LENGTH_SHORT).show()
    }
}
