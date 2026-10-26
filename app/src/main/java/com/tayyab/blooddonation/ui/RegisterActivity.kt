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
import com.tayyab.blooddonation.util.SupabaseAuth
import com.tayyab.blooddonation.util.SupabaseSync

/**
 * Donor registration, v1.4: the account is a real Supabase Auth
 * account, not a local-only row.
 *
 * The flow, all network on a background thread:
 * 1. If the build has no Supabase config, registration stops at once
 *    with "Registration needs an internet connection" — it NEVER
 *    creates a silent local-only account any more (that silent
 *    fallback is exactly the confusion v1.3 caused).
 * 2. signUp through Supabase Auth. On failure (email already
 *    registered, no network, ...) the error is shown and NOTHING is
 *    created — no Auth user is kept waiting, no local row either.
 * 3. On success, the Auth session is saved FIRST, so the donor
 *    insert that follows carries the user's own JWT (the
 *    "authenticated" RLS policies reject the anon key alone). The
 *    donor profile is inserted online WITH auth_id = the new Auth
 *    user id, and the server-assigned donor id is used locally.
 * 4. The local user row is keyed by email (username = email, salted
 *    hash as before, role DONOR, donor_id = server id), and the
 *    session is saved with email + role + donor id + Auth tokens.
 * 5. If the donor-profile insert fails AFTER a successful sign-up,
 *    the account still works on this phone (local donor + local row),
 *    but the app says so honestly: the profile did not sync, and the
 *    profile screen offers a "Sync profile" retry. Success is never
 *    claimed for a sync that did not happen.
 */
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
            val email = findViewById<EditText>(R.id.etEmail).text.toString().trim()
            val password = findViewById<EditText>(R.id.etPassword).text.toString()
            val name = findViewById<EditText>(R.id.etName).text.toString().trim()
            val age = findViewById<EditText>(R.id.etAge).text.toString().trim().toIntOrNull()
            val weight = findViewById<EditText>(R.id.etWeight).text.toString().trim().toDoubleOrNull()
            val phone = findViewById<EditText>(R.id.etPhone).text.toString().trim()
            val city = findViewById<EditText>(R.id.etCity).text.toString().trim()

            if (email.isEmpty() || !email.contains("@") || password.isEmpty() ||
                name.isEmpty() || age == null || weight == null ||
                phone.isEmpty() || city.isEmpty()
            ) {
                Toast.makeText(
                    this, "Please fill all fields marked * (a valid email is required)",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (db.getUser(email) != null) {
                Toast.makeText(this, "Email already registered on this phone", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!SupabaseSync.isOnline) {
                Toast.makeText(this, "Registration needs an internet connection", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val donor = Donor(
                name = name, age = age, gender = spGender.selectedItem.toString(),
                bloodGroup = spGroup.selectedItem.toString(), phone = phone,
                city = city, weightKg = weight, lastDonationDate = null
            )
            Thread {
                val auth = SupabaseAuth.signUp(email, password)
                if (auth == null) {
                    val message = if (SupabaseAuth.lastError == "No internet connection") {
                        "Registration needs an internet connection"
                    } else {
                        SupabaseAuth.lastError ?: "Sign-up failed"
                    }
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                // Save the session NOW, so the insert below goes out
                // under the user's JWT (RLS needs it). donorId is
                // filled in once the server id is known.
                Session.saveAuth(
                    this, email, Session.ROLE_DONOR, null,
                    email, auth.userId, auth.accessToken, auth.refreshToken
                )
                val remoteId = SupabaseSync.addDonorRemote(donor, auth.userId)
                if (remoteId != null) {
                    val donorId = db.registerDonorAccountWithId(
                        donor.copy(id = remoteId), email,
                        Session.hashPassword(email, password)
                    )
                    Session.saveAuth(
                        this, email, Session.ROLE_DONOR, donorId,
                        email, auth.userId, auth.accessToken, auth.refreshToken
                    )
                    Session.setProfilePendingSync(this, false)
                    runOnUiThread {
                        Toast.makeText(this, "Account created. Welcome, $name", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                } else {
                    // Signed up, but the profile did not reach
                    // Supabase: keep the account usable on this phone,
                    // mark the profile pending, and say so plainly.
                    val donorId = db.registerDonorAccount(
                        donor, email, Session.hashPassword(email, password)
                    )
                    Session.saveAuth(
                        this, email, Session.ROLE_DONOR, donorId,
                        email, auth.userId, auth.accessToken, auth.refreshToken
                    )
                    Session.setProfilePendingSync(this, true)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Account created, but the donor profile did not sync. " +
                                "Retry from your profile screen.",
                            Toast.LENGTH_LONG
                        ).show()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
            }.start()
        }
    }
}
