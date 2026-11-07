package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.EmergencyLogic
import com.tayyab.blooddonation.util.OfflineQueue
import java.time.LocalDate

/**
 * Phase 3: offline emergency mode. Opens straight to a cached list of the most
 * urgent recent requests (so the app is still useful with no signal), and lets the
 * user compose a new request that is queued locally and flushed when the network
 * returns. The queue lives in [OfflineQueue]; de-dup uses [EmergencyLogic].
 */
class EmergencyActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency)
        title = getString(R.string.title_emergency)
        db = DbHelper(this)
        prefs = getSharedPreferences("blood_donation", MODE_PRIVATE)
        container = findViewById(R.id.containerCached)

        val urgencies = listOf("Normal", "Urgent", "Critical")
        val sp = findViewById<Spinner>(R.id.spEmUrgency)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, urgencies)
        sp.setSelection(1) // default Urgent

        findViewById<Button>(R.id.btnQueue).setOnClickListener { queueRequest(sp) }
    }

    override fun onResume() {
        super.onResume()
        render()
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

    private fun queueRequest(sp: Spinner) {
        val bg = findViewById<EditText>(R.id.etEmBg).text.toString().trim()
        val units = findViewById<EditText>(R.id.etEmUnits).text.toString().toIntOrNull() ?: 0
        val city = findViewById<EditText>(R.id.etEmCity).text.toString().trim()
        val hospital = findViewById<EditText>(R.id.etEmHospital).text.toString().trim()
        val urgency = sp.selectedItem as String
        if (bg.isBlank() || units <= 0 || city.isBlank() || hospital.isBlank()) {
            Toast.makeText(this, R.string.emergency_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val p = com.tayyab.blooddonation.util.PendingRequest(
            bloodGroup = bg, units = units, city = city, hospital = hospital,
            urgency = urgency, queuedAt = LocalDate.now().toString()
        )
        if (OfflineQueue.add(prefs, p)) {
            Toast.makeText(this, R.string.emergency_queued_toast, Toast.LENGTH_SHORT).show()
            findViewById<EditText>(R.id.etEmBg).text.clear()
            findViewById<EditText>(R.id.etEmUnits).text.clear()
            findViewById<EditText>(R.id.etEmCity).text.clear()
            findViewById<EditText>(R.id.etEmHospital).text.clear()
            render()
        } else {
            Toast.makeText(this, R.string.emergency_duplicate, Toast.LENGTH_LONG).show()
        }
    }
}
