package com.tayyab.blooddonation.ui

import android.content.Intent
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
import com.tayyab.blooddonation.util.SupabaseSync

class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        title = "Register as a Donor"
        val db = DbHelper(this)

        val spGender = findViewById<Spinner>(R.id.spGender)
        spGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Male", "Female", "Other"))
        val spGroup = findViewById<Spinner>(R.id.spBloodGroup)
        spGroup.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            BloodCompatibility.ALL_GROUPS)

        findViewById<Button>(R.id.btnCreateAccount).setOnClickListener {
            val username = findViewById<EditText>(R.id.etUsername).text.toString().trim()
            val password = findViewById<EditText>(R.id.etPassword).text.toString()
            val name = findViewById<EditText>(R.id.etName).text.toString().trim()
            val age = findViewById<EditText>(R.id.etAge).text.toString().trim().toIntOrNull()
            val weight = findViewById<EditText>(R.id.etWeight).text.toString().trim().toDoubleOrNull()
            val phone = findViewById<EditText>(R.id.etPhone).text.toString().trim()
            val city = findViewById<EditText>(R.id.etCity).text.toString().trim()

            if (username.isEmpty() || password.isEmpty() || name.isEmpty() ||
                age == null || weight == null || phone.isEmpty() || city.isEmpty()
            ) {
                Toast.makeText(this, "Please fill all fields marked *", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (db.getUser(username) != null) {
                Toast.makeText(this, "Username already taken", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val donor = Donor(
                name = name, age = age, gender = spGender.selectedItem.toString(),
                bloodGroup = spGroup.selectedItem.toString(), phone = phone,
                city = city, weightKg = weight, lastDonationDate = null
            )
            // The account itself always stays local (no Supabase Auth);
            // only the donor profile goes online, same as AddDonor.
            if (SupabaseSync.isOnline) {
                Thread {
                    val remoteId = SupabaseSync.addDonorRemote(donor)
                    val donorId = if (remoteId != null) {
                        db.registerDonorAccountWithId(
                            donor.copy(id = remoteId), username,
                            Session.hashPassword(username, password)
                        )
                    } else {
                        db.registerDonorAccount(
                            donor, username, Session.hashPassword(username, password)
                        )
                    }
                    runOnUiThread {
                        Session.save(this, username, Session.ROLE_DONOR, donorId)
                        Toast.makeText(
                            this,
                            if (remoteId != null) "Account created. Welcome, $name"
                            else "Account created on this phone (not synced to Supabase).",
                            Toast.LENGTH_SHORT
                        ).show()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }.start()
            } else {
                val donorId = db.registerDonorAccount(
                    donor, username, Session.hashPassword(username, password)
                )
                Session.save(this, username, Session.ROLE_DONOR, donorId)
                Toast.makeText(this, "Account created. Welcome, $name", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}
