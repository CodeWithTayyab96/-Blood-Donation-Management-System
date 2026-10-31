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
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync
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
            // Parse defensively: toInt()/toDouble() throw on "25.5", "2 5" or
            // any value outside the type's range, which would crash the app.
            val age = ageText.toIntOrNull()
            if (age == null || age !in BloodCompatibility.MIN_AGE..BloodCompatibility.MAX_AGE) {
                Toast.makeText(
                    this,
                    "Age must be a whole number between " +
                        "${BloodCompatibility.MIN_AGE} and ${BloodCompatibility.MAX_AGE}",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val weight = weightText.toDoubleOrNull()
            if (weight == null || weight < BloodCompatibility.MIN_WEIGHT_KG) {
                Toast.makeText(
                    this,
                    "Weight must be a number of at least " +
                        "${BloodCompatibility.MIN_WEIGHT_KG.toInt()} kg",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val donor = Donor(
                name = name,
                age = age,
                gender = spGender.selectedItem.toString(),
                bloodGroup = spGroup.selectedItem.toString(),
                phone = phone,
                city = city,
                weightKg = weight,
                lastDonationDate = lastDonation?.toString()
            )
            if (SupabaseSync.isOnline) {
                // Online first so the donor keeps its server id locally
                // too; if Supabase is unreachable, save locally only.
                Background.run {
                    val remoteId = SupabaseSync.addDonorRemote(donor)
                    if (remoteId != null) {
                        db.insertDonorWithId(donor.copy(id = remoteId))
                    } else {
                        db.addDonor(donor)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (remoteId != null) "Donor registered: ${donor.name}"
                            else "Donor saved on this phone (not synced to Supabase).",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            } else {
                db.addDonor(donor)
                Toast.makeText(this, "Donor registered: ${donor.name}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
