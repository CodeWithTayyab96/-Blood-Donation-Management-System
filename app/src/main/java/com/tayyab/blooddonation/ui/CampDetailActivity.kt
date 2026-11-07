package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Session
import java.time.LocalDate

/**
 * Phase 3: a single camp. A donor can check in (recorded locally); everyone sees
 * the attendance count and the names of those who checked in. Creating camps is a
 * manager action done from [AddCampActivity].
 */
class CampDetailActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private var campId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_detail)
        db = DbHelper(this)
        campId = intent.getLongExtra("campId", -1)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val camp = db.getAllCamps().firstOrNull { it.id == campId } ?: run {
            finish()
            return
        }
        title = camp.name
        findViewById<TextView>(R.id.tvCampName).text = camp.name
        findViewById<TextView>(R.id.tvCampDate).text = camp.campDate
        findViewById<TextView>(R.id.tvCampLocation).text = camp.location
        findViewById<TextView>(R.id.tvCampCity).text = camp.city
        findViewById<TextView>(R.id.tvCampOrganizer).text = camp.organizer.ifBlank { "—" }

        val attendance = db.getAttendance(campId)
        findViewById<TextView>(R.id.tvAttendance).text =
            getString(R.string.camp_attendance_count, attendance.size)
        val names = attendance.mapNotNull { db.getDonor(it.donorId)?.name }
        findViewById<TextView>(R.id.tvAttendees).text =
            if (names.isEmpty()) getString(R.string.camp_no_attendance) else names.joinToString("\n")

        // Only a signed-in donor can check in; managers just read the list.
        Session.load(this)
        val donorId = Session.donorId
        val btn = findViewById<Button>(R.id.btnCheckIn)
        val checkedIn = findViewById<TextView>(R.id.tvCheckedIn)
        if (donorId == null) {
            btn.visibility = View.GONE
            checkedIn.visibility = View.GONE
        } else if (db.hasAttended(campId, donorId)) {
            btn.visibility = View.GONE
            checkedIn.visibility = View.VISIBLE
        } else {
            btn.visibility = View.VISIBLE
            checkedIn.visibility = View.GONE
            btn.setOnClickListener {
                db.recordAttendance(campId, donorId, LocalDate.now().toString())
                Toast.makeText(this, R.string.camp_attended, Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }
}
