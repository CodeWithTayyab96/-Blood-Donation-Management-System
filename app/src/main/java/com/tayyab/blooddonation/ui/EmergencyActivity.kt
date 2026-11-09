package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.tayyab.blooddonation.util.BloodCompatibility
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.EmergencyFlush
import com.tayyab.blooddonation.util.EmergencyLogic
import com.tayyab.blooddonation.util.OfflineQueue
import com.tayyab.blooddonation.util.P2pRules
import com.tayyab.blooddonation.util.PendingRequest
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseClient
import com.tayyab.blooddonation.util.SupabaseSync
import java.time.LocalDate

/**
 * Phase 3: emergency mode. Opens to a cached list of the most urgent recent
 * requests, and lets the user compose a new request.
 *
 * The screen used to call itself "Emergency (Offline)" no matter what, even
 * with the phone online — the label was fixed text, and the screen never
 * checked the network. It now shows the live state: when the server answers,
 * new requests send right away and anything queued while offline is sent;
 * when it does not answer, requests are queued on the phone and sent when
 * the connection returns (also by the background alert worker, so the user
 * does not have to reopen this screen).
 */
class EmergencyActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var container: LinearLayout

    /** Last live check: true = the server answered, false = it did not, null = not checked yet. */
    private var onlineNow: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_emergency)
        title = getString(R.string.title_emergency)
        db = DbHelper(this)
        prefs = getSharedPreferences("blood_donation", MODE_PRIVATE)
        container = findViewById(R.id.containerCached)

        val spBg = findViewById<Spinner>(R.id.spEmBg)
        spBg.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, BloodCompatibility.ALL_GROUPS)

        val urgencies = listOf("Normal", "Urgent", "Critical")
        val sp = findViewById<Spinner>(R.id.spEmUrgency)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, urgencies)
        sp.setSelection(1) // default Urgent

        findViewById<Button>(R.id.btnQueue).setOnClickListener { queueRequest(spBg, sp) }
    }

    override fun onResume() {
        super.onResume()
        render()
        checkConnectionAndFlush()
    }

    /**
     * The live check: does the server actually answer? [SupabaseSync.isOnline]
     * only says the build holds a Supabase URL and key, which is not the same
     * thing — the phone can hold both and still have no route to the server.
     * Runs off the UI thread; the probe can take seconds on a dead network,
     * so the screen stays usable on the queue path meanwhile.
     */
    private fun checkConnectionAndFlush() {
        setStatus(R.string.emergency_status_checking, R.string.emergency_compose_title, R.string.label_queue_request)
        if (!SupabaseSync.isOnline) {
            onlineNow = false
            setStatus(R.string.emergency_status_offline, R.string.emergency_compose_title, R.string.label_queue_request)
            return
        }
        Background.run {
            val online = SupabaseClient.probeServer().ok
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                onlineNow = online
                if (online) {
                    setStatus(R.string.emergency_status_online, R.string.emergency_compose_title_online, R.string.label_post_request)
                    flushQueued()
                } else {
                    setStatus(R.string.emergency_status_offline, R.string.emergency_compose_title, R.string.label_queue_request)
                }
            }
        }
    }

    private fun setStatus(statusRes: Int, composeRes: Int, buttonRes: Int) {
        findViewById<TextView>(R.id.tvEmStatus).text = getString(statusRes)
        findViewById<TextView>(R.id.tvComposeTitle).text = getString(composeRes)
        findViewById<Button>(R.id.btnQueue).text = getString(buttonRes)
    }

    /** Sends whatever was queued while offline, now that the server answers. */
    private fun flushQueued() {
        Background.run {
            val result = EmergencyFlush.flush(this@EmergencyActivity)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render()
                if (result.sent > 0) {
                    Toast.makeText(this, getString(R.string.emergency_flushed, result.sent), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun render() {
        val cached = EmergencyLogic.cachedForOffline(db.getAllRequests())
        container.removeAllViews()
        cached.forEach { r ->
            val tv = TextView(this).apply {
                text = "${r.bloodGroup} • ${r.urgency} • ${r.unitsNeeded} unit(s) • ${r.city} • ${r.hospital}"
                textSize = 14f
                setPadding(0, 6, 0, 6)
            }
            container.addView(tv)
        }
        findViewById<TextView>(R.id.tvCachedEmpty).visibility =
            if (cached.isEmpty()) View.VISIBLE else View.GONE

        val queued = OfflineQueue.all(prefs)
        val tvQueued = findViewById<TextView>(R.id.tvQueued)
        tvQueued.visibility = if (queued.isNotEmpty()) View.VISIBLE else View.GONE
        tvQueued.text = getString(R.string.emergency_queued, queued.size)
    }

    private fun queueRequest(spBg: Spinner, sp: Spinner) {
        val bg = spBg.selectedItem as String
        val units = findViewById<EditText>(R.id.etEmUnits).text.toString().toIntOrNull() ?: 0
        val city = findViewById<EditText>(R.id.etEmCity).text.toString().trim()
        val hospital = findViewById<EditText>(R.id.etEmHospital).text.toString().trim()
        val urgency = sp.selectedItem as String
        if (bg.isBlank() || units <= 0 || city.isBlank() || hospital.isBlank()) {
            Toast.makeText(this, R.string.emergency_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val p = PendingRequest(
            bloodGroup = bg, units = units, city = city, hospital = hospital,
            urgency = urgency, queuedAt = LocalDate.now().toString()
        )
        // Online: send it now instead of queueing it. Offline, or not yet
        // checked: queue it. The check on resume — or the background
        // worker — sends it once the server answers.
        if (onlineNow == true) {
            postNow(p)
            return
        }
        val requesterId = Session.authUserId
        if (requesterId != null) {
            val openRemote = db.openRequestCountForRequester(requesterId)
            val queued = OfflineQueue.all(prefs).size
            if (openRemote + queued >= P2pRules.MAX_OPEN_REQUESTS) {
                Toast.makeText(
                    this,
                    getString(R.string.emergency_cap, P2pRules.MAX_OPEN_REQUESTS),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        if (OfflineQueue.add(prefs, p)) {
            Toast.makeText(this, R.string.emergency_queued_toast, Toast.LENGTH_SHORT).show()
            clearForm()
            render()
        } else {
            Toast.makeText(this, R.string.emergency_duplicate, Toast.LENGTH_LONG).show()
        }
    }

    /** Sends one emergency request straight to the server, no queue involved. */
    private fun postNow(p: PendingRequest) {
        val requesterId = Session.authUserId
        if (requesterId.isNullOrBlank()) {
            Toast.makeText(this, R.string.emergency_sign_in_needed, Toast.LENGTH_LONG).show()
            return
        }
        if (db.openRequestCountForRequester(requesterId) >= P2pRules.MAX_OPEN_REQUESTS) {
            Toast.makeText(
                this,
                getString(R.string.emergency_cap, P2pRules.MAX_OPEN_REQUESTS),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val contact = Session.donorId?.let { db.getDonor(it)?.phone }.orEmpty()
        val req = EmergencyLogic.toRequest(p, requesterId, contact, LocalDate.now().toString())
        val btn = findViewById<Button>(R.id.btnQueue)
        btn.isEnabled = false
        Background.run {
            val remoteId = SupabaseSync.addRequestRemote(req)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btn.isEnabled = true
                if (remoteId == null) {
                    // The server did not take it — keep the request by
                    // queueing it instead of losing it.
                    if (OfflineQueue.add(prefs, p)) {
                        Toast.makeText(this, R.string.emergency_queued_toast, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, R.string.emergency_duplicate, Toast.LENGTH_LONG).show()
                    }
                    clearForm()
                    render()
                    return@runOnUiThread
                }
                db.insertRequestWithId(req.copy(id = remoteId))
                Toast.makeText(this, R.string.emergency_posted_toast, Toast.LENGTH_SHORT).show()
                clearForm()
                render()
            }
        }
    }

    private fun clearForm() {
        findViewById<Spinner>(R.id.spEmBg).setSelection(0)
        findViewById<EditText>(R.id.etEmUnits).text.clear()
        findViewById<EditText>(R.id.etEmCity).text.clear()
        findViewById<EditText>(R.id.etEmHospital).text.clear()
    }
}
