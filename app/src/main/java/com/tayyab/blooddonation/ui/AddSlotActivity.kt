package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.AppointmentSlot

/** Phase 3: manager-only creation of an appointment slot with a capacity guard. */
class AddSlotActivity : AppCompatActivity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_slot)
        title = getString(R.string.label_add_slot)
        db = DbHelper(this)

        val etTime = findViewById<EditText>(R.id.etSlotTime)
        val etCapacity = findViewById<EditText>(R.id.etSlotCapacity)
        val etNote = findViewById<EditText>(R.id.etSlotNote)

        findViewById<Button>(R.id.btnSaveSlot).setOnClickListener {
            val time = etTime.text.toString().trim()
            val capacity = etCapacity.text.toString().toIntOrNull() ?: 0
            val note = etNote.text.toString().trim()
            if (time.isBlank() || capacity < 1) {
                Toast.makeText(this, R.string.slot_invalid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            db.insertSlot(
                AppointmentSlot(slotTime = time, capacity = capacity, booked = 0, note = note)
            )
            Toast.makeText(this, R.string.slot_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
