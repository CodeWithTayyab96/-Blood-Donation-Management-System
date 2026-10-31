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
import com.tayyab.blooddonation.util.Background
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
                name.isEmpty() || phone.isEmpty() || city.isEmpty()
            ) {
                Toast.makeText(
                    this, "Please fill all fields marked * (a valid email is required)",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // Range checks (M12). A donor with an impossible age or weight is
            // still a bad record even though it never crashes, and the same
            // rules are applied in AddDonorActivity.
            if (age == null || age !in BloodCompatibility.MIN_AGE..BloodCompatibility.MAX_AGE) {
                Toast.makeText(
                    this,
                    "Age must be a whole number between " +
                        "${BloodCompatibility.MIN_AGE} and ${BloodCompatibility.MAX_AGE}",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (weight == null || weight < BloodCompatibility.MIN_WEIGHT_KG) {
                Toast.makeText(
                    this,
                    "Weight must be a number of at least " +
                        "${BloodCompatibility.MIN_WEIGHT_KG.toInt()} kg",
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
            Background.run {
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
                    return@run
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
                        Session.hashPassword(password)
                    )
                    // -1 means the local account row could not be written (the
                    // email already exists in the users table). The donor insert
                    // was rolled back with it, so nothing is half-created. The
                    // Auth account does exist, so point the user at sign-in.
                    if (donorId <= 0) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Your account exists but could not be set up on this phone. " +
                                    "Please sign in with your email and password.",
                                Toast.LENGTH_LONG
                            ).show()
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        return@run
                    }
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
                        donor, email, Session.hashPassword(password)
                    )
                    // -1 means the local account row could not be written
                    // (the email is already in the users table). The donor
                    // insert was rolled back with it, so nothing is left
                    // half-created — just report it.
                    if (donorId <= 0) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Could not create the local account. Please sign in and try again.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@run
                    }
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
            }
        }
    }
}
