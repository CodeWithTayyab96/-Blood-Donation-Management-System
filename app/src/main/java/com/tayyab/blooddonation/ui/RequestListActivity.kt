package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.P2pRules
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync

/**
 * P2P: one screen, two views.
 *
 * - **Browse** — every open request other people posted, nearest city
 *   first, with the ones this donor's group can give to first. Everyone
 *   sees everything: a donor whose group does not match may still know
 *   someone off the app who can give, and can share the request with them.
 *   Requests with no `requester_id` (the manager's own, and every row
 *   created before P2P) are deliberately left out: they are fulfilled from
 *   blood-bank stock by the manager, not offered on by peers.
 * - **My requests** — this user's own requests, any status, with Cancel.
 *
 * Managers skip both and get the plain full list, exactly as before.
 */
class RequestListActivity : AppCompatActivity() {

    companion object {
        /** Open the list on a particular tab: the user home links to both. */
        const val EXTRA_MODE = "mode"
        const val MODE_BROWSE = "browse"
        const val MODE_MINE = "mine"
    }

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

        // Bottom navigation (v1.22): this is the Requests tab.
        BottomNav.setup(this, R.id.nav_requests)
        BottomNav.setupChatFab(this)

        val rv = findViewById<RecyclerView>(R.id.rvRequests)
        rv.setHasFixedSize(true)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = RequestAdapter(
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
            // Arriving from the home screen: open the tab that was asked for.
            if (intent?.getStringExtra(EXTRA_MODE) == MODE_MINE) switchTo(Mode.MINE)
        }

        findViewById<Button>(R.id.btnNewRequest).setOnClickListener {
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

    /** Three-dot menu in the app bar: the secondary destinations. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean =
        BottomNav.onCreateOptionsMenu(this, menu)

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        BottomNav.onPrepareOptionsMenu(this, menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        BottomNav.onOptionsItemSelected(this, item) || super.onOptionsItemSelected(item)

    private fun switchTo(next: Mode) {
        mode = next
        // Rebuild with cancel buttons only in "My requests".
        val rv = findViewById<RecyclerView>(R.id.rvRequests)
        adapter = RequestAdapter(
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
        val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
        val label = findViewById<android.widget.TextView>(R.id.syncLabel)
        if (SupabaseSync.isOnline) {
            spinner.visibility = android.view.View.VISIBLE
            label.visibility = android.view.View.VISIBLE
            label.text = "Cancelling request…"
            Background.run {
                val ok = SupabaseSync.cancelRequestRemote(req.id)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    spinner.visibility = android.view.View.GONE
                    label.visibility = android.view.View.GONE
                    Toast.makeText(
                        this,
                        if (ok) "Request cancelled"
                        else "Cancelled on this phone (not synced to Supabase).",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            spinner.visibility = android.view.View.GONE
            label.visibility = android.view.View.GONE
            Toast.makeText(this, "Request cancelled on this phone.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
            val label = findViewById<android.widget.TextView>(R.id.syncLabel)
            spinner.visibility = android.view.View.VISIBLE
            label.visibility = android.view.View.VISIBLE
            label.text = "Updating requests…"
            Background.run {
                SupabaseSync.pullRequests(db)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        renderFromLocal()
                        spinner.visibility = android.view.View.GONE
                        label.visibility = android.view.View.GONE
                    }
                }
            }
        }
    }

    private fun renderFromLocal() {
        val me = Session.authUserId
        val isManager = Session.isManager
        val currentMode = mode
        Background.run {
            val items = when {
                isManager -> db.getAllRequests()
                currentMode == Mode.MINE -> myRequests(me)
                else -> browseRequests(me)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(items)
                val empty = findViewById<TextView>(R.id.tvEmptyRequests)
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                empty.text = when {
                    isManager -> "No requests yet."
                    currentMode == Mode.MINE ->
                        "You have not posted a request yet. Use New request to ask for blood."
                    else -> "No open requests right now."
                }
                BottomNav.updateBadges(this@RequestListActivity)
            }
        }
    }

    /** Requests this user posted, any status, newest first. */
    private fun myRequests(me: String?): List<BloodRequest> {
        if (me.isNullOrBlank()) return emptyList()
        return db.getRequestsForRequester(me)
    }

    /** Open peer requests from other people: every one of them, compatible
     *  first, nearest first. */
    private fun browseRequests(me: String?): List<BloodRequest> {
        val donor = Session.donorId?.let { db.getDonor(it) }
        val myCity = donor?.city
        val today = java.time.LocalDate.now().toString()
        return db.getOpenPeerRequests(me)
            .asSequence()
            // Lazy expiry: a request past its needed-by date and still open is
            // Expired. There is no pg_cron, so this is evaluated on read.
            .map { it.copy(status = P2pRules.requestStatusOnRead(it.status, it.neededBy, today)) }
            .filter { P2pRules.isOpenRequest(it.status) }
            // City-first, then compatible-first, then most urgent, then newest.
            .sortedWith(
                compareByDescending<BloodRequest> { !myCity.isNullOrBlank() && it.city == myCity }
                    .thenByDescending { donor != null && BloodCompatibility.isCompatible(donor.bloodGroup, it.bloodGroup) }
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
