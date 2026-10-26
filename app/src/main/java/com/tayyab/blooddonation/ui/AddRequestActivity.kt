package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.SupabaseSync
import java.time.LocalDate

class AddRequestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_request)
        title = "New Blood Request"
        val db = DbHelper(this)

        val spGroup = findViewById<Spinner>(R.id.spReqGroup)
        spGroup.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            BloodCompatibility.ALL_GROUPS)
        val spUrgency = findViewById<Spinner>(R.id.spUrgency)
        spUrgency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Normal", "Urgent", "Critical"))

        findViewById<Button>(R.id.btnSaveRequest).setOnClickListener {
            val patient = findViewById<EditText>(R.id.etPatient).text.toString().trim()
            val hospital = findViewById<EditText>(R.id.etHospital).text.toString().trim()
            val unitsText = findViewById<EditText>(R.id.etUnits).text.toString().trim()
            val contact = findViewById<EditText>(R.id.etContact).text.toString().trim()

            if (patient.isEmpty() || hospital.isEmpty() || unitsText.isEmpty() || contact.isEmpty()) {
                Toast.makeText(this, "Please fill all fields marked *", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val req = BloodRequest(
                patientName = patient,
                hospital = hospital,
                bloodGroup = spGroup.selectedItem.toString(),
                unitsNeeded = unitsText.toInt(),
                urgency = spUrgency.selectedItem.toString(),
                contact = contact,
                createdAt = LocalDate.now().toString()
            )
            if (SupabaseSync.isOnline) {
                // Online first so the request keeps its server id locally;
                // unreachable Supabase = saved locally only.
                Thread {
                    val remoteId = SupabaseSync.addRequestRemote(req)
                    if (remoteId != null) {
                        db.insertRequestWithId(req.copy(id = remoteId))
                    } else {
                        db.addRequest(req)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (remoteId != null) "Request created for $patient"
                            else "Request saved on this phone (not synced to Supabase).",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }.start()
            } else {
                db.addRequest(req)
                Toast.makeText(this, "Request created for $patient", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
