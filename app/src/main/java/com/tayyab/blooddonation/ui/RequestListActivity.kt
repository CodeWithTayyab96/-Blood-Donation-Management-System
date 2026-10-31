package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.P2pRules
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync

/**
 * P2P: one screen, two views.
 *
 * - **Browse** — open requests other people posted, that this donor's group
 *   can actually give to, nearest city first. Requests with no
 *   `requester_id` (the manager's own, and every row created before P2P) are
 *   deliberately left out: they are fulfilled from blood-bank stock by the
 *   manager, not offered on by peers.
 * - **My requests** — this user's own requests, any status, with Cancel.
 *
 * Managers skip both and get the plain full list, exactly as before.
 */
class RequestListActivity : AppCompatActivity() {

    private enum class Mode { BROWSE, MINE }

    private lateinit var db: DbHelper
    private lateinit var adapter: RequestAdapter
    private var mode = Mode.BROWSE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_request_list)
        title = "Blood Requests"
        db = DbHelper(this)

        val rv = findViewById<RecyclerView>(R.id.rvRequests)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = RequestAdapter(
            items = emptyList(),
            onClick = { req -> openDetail(req) },
            showCancel = false,
            onCancel = { req -> confirmCancel(req) }
        )
        rv.adapter = adapter

        val tabs = findViewById<View>(R.id.tabsRow)
        // A manager has no "mine" and no "browse": they own the whole list.
        if (Session.isManager) {
            tabs.visibility = View.GONE
        } else {
            findViewById<Button>(R.id.btnTabBrowse).setOnClickListener { switchTo(Mode.BROWSE) }
            findViewById<Button>(R.id.btnTabMine).setOnClickListener { switchTo(Mode.MINE) }
        }

        findViewById<FloatingActionButton>(R.id.fabAddRequest).setOnClickListener {
            // Managers raise a request on behalf of a patient and fulfil it
            // from stock (AddRequestActivity). Everyone else posts a peer
            // request of their own, which donors can offer to help with.
            startActivity(
                Intent(
                    this,
                    if (Session.isManager) AddRequestActivity::class.java
                    else CreateRequestActivity::class.java
                )
            )
        }
    }

    private fun switchTo(next: Mode) {
        mode = next
        // Rebuild with cancel buttons only in "My requests".
        val rv = findViewById<RecyclerView>(R.id.rvRequests)
        adapter = RequestAdapter(
            items = emptyList(),
            onClick = { req -> openDetail(req) },
            showCancel = (next == Mode.MINE),
            onCancel = { req -> confirmCancel(req) }
        )
        rv.adapter = adapter
        renderFromLocal()
    }

    private fun openDetail(req: BloodRequest) {
        val i = Intent(this, RequestDetailActivity::class.java)
        i.putExtra("requestId", req.id)
        startActivity(i)
    }

    /** Cancel needs confirming: it closes the request for every donor who
     *  was going to help, and cannot be undone from the app. */
    private fun confirmCancel(req: BloodRequest) {
        AlertDialog.Builder(this)
            .setTitle("Cancel this request?")
            .setMessage(
                "This closes the request for everyone. Any donor who offered " +
                    "will no longer be able to help."
            )
            .setPositiveButton("Cancel request") { _, _ -> doCancel(req) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun doCancel(req: BloodRequest) {
        db.setRequestStatus(req.id, "Cancelled")
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            Background.run {
                val ok = SupabaseSync.cancelRequestRemote(req.id)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        if (ok) "Request cancelled"
                        else "Cancelled on this phone (not synced to Supabase).",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            Toast.makeText(this, "Request cancelled on this phone.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            Background.run {
                SupabaseSync.pullRequests(db)
                runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
            }
        }
    }

    private fun renderFromLocal() {
        val me = Session.authUserId
        val items = when {
            Session.isManager -> db.getAllRequests()
            mode == Mode.MINE -> myRequests(me)
            else -> browseRequests(me)
        }
        adapter.submit(items)
        val empty = findViewById<TextView>(R.id.tvEmptyRequests)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            Session.isManager -> "No requests yet."
            mode == Mode.MINE ->
                "You have not posted a request yet. Use + to ask for blood."
            else ->
                "No open requests you can help with right now. " +
                    "Only requests other people posted, matching your blood " +
                    "group, appear here."
        }
    }

    /** Requests this user posted, any status, newest first. */
    private fun myRequests(me: String?): List<BloodRequest> {
        if (me.isNullOrBlank()) return emptyList()
        return db.getAllRequests().filter { it.requesterId == me }
    }

    /** Open peer requests from other people, compatible first, nearest first. */
    private fun browseRequests(me: String?): List<BloodRequest> {
        val donor = Session.donorId?.let { db.getDonor(it) }
        val myCity = donor?.city
        val today = java.time.LocalDate.now().toString()
        return db.getAllRequests()
            .asSequence()
            // Peer requests only: a null requester_id is a manager row.
            .filter { !it.requesterId.isNullOrBlank() }
            // Never my own request — I cannot offer to help myself.
            .filter { me.isNullOrBlank() || it.requesterId != me }
            .filter { P2pRules.isOpenRequest(it.status) }
            // Only requests my blood group can actually give to. Without a
            // donor profile there is no group to check against, so show all.
            .filter { donor == null || BloodCompatibility.isCompatible(donor.bloodGroup, it.bloodGroup) }
            // Lazy expiry: a request past its needed-by date and still open is
            // Expired. There is no pg_cron, so this is evaluated on read.
            .map { it.copy(status = P2pRules.requestStatusOnRead(it.status, it.neededBy, today)) }
            // City-first, then most urgent, then newest.
            .sortedWith(
                compareByDescending<BloodRequest> { !myCity.isNullOrBlank() && it.city == myCity }
                    .thenByDescending { urgencyRank(it.urgency) }
                    .thenByDescending { it.id }
            )
            .toList()
    }

    private fun urgencyRank(urgency: String): Int = when (urgency) {
        "Critical" -> 3
        "Urgent" -> 2
        else -> 1
    }
}
