package com.tayyab.blooddonation.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session
import java.time.LocalDate
import java.util.Calendar

class AddDonorActivity : AppCompatActivity() {

    private var lastDonation: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        if (!Session.isManager) {
            Toast.makeText(this, "Only managers can add donors", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_add_donor)
        title = "Register Donor"
        val db = DbHelper(this)

        val spGender = findViewById<Spinner>(R.id.spGender)
        spGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Male", "Female", "Other"))
        val spGroup = findViewById<Spinner>(R.id.spBloodGroup)
        spGroup.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            BloodCompatibility.ALL_GROUPS)

        val btnDate = findViewById<Button>(R.id.btnPickDate)
        btnDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                lastDonation = LocalDate.of(y, m + 1, d)
                btnDate.text = "Last donation date: $lastDonation"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        findViewById<Button>(R.id.btnSaveDonor).setOnClickListener {
            val name = findViewById<EditText>(R.id.etName).text.toString().trim()
            val ageText = findViewById<EditText>(R.id.etAge).text.toString().trim()
            val weightText = findViewById<EditText>(R.id.etWeight).text.toString().trim()
            val phone = findViewById<EditText>(R.id.etPhone).text.toString().trim()
            val city = findViewById<EditText>(R.id.etCity).text.toString().trim()

            if (name.isEmpty() || ageText.isEmpty() || weightText.isEmpty() || phone.isEmpty() || city.isEmpty()) {
                Toast.makeText(this, "Please fill all fields marked *", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val donor = Donor(
                name = name,
                age = ageText.toInt(),
                gender = spGender.selectedItem.toString(),
                bloodGroup = spGroup.selectedItem.toString(),
                phone = phone,
                city = city,
                weightKg = weightText.toDouble(),
                lastDonationDate = lastDonation?.toString()
            )
            db.addDonor(donor)
            Toast.makeText(this, "Donor registered: ${donor.name}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
