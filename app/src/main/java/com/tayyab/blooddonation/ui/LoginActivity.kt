package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.UserAccount
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseAuth
import com.tayyab.blooddonation.util.SupabaseSync

/**
 * Sign-in, v1.4. The single field accepts either form:
 *
 * (a) An input containing "@" is an email, and goes through Supabase
 * Auth: signIn online, then resolve the account. A local row for the
 * email (returning donors, and the seeded manager@blood.local) gives
 * the role and donor id straight away; otherwise the donor profile is
 * looked up online by auth_id, and a local row is created from it.
 * If neither exists, the app says "No donor profile linked to this
 * account" and does NOT log in. If the Auth sign-in fails ONLY
 * because there is no network, and a local row for that email exists
 * with a matching password hash, the offline fallback login is
 * allowed and the toast says "Signed in offline".
 *
 * (b) An input WITHOUT "@" keeps the exact v1.3 local-only login
 * unchanged — this preserves the demo login manager / manager123.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        if (Session.isLoggedIn) {
            goToMain()
            return
        }
        setContentView(R.layout.activity_login)
        title = "Sign In"
        val db = DbHelper(this)

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val input = findViewById<EditText>(R.id.etUsername).text.toString().trim()
            val password = findViewById<EditText>(R.id.etPassword).text.toString()
            if (input.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter email or username and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!input.contains("@")) {
                // (b) Local-only login, exactly as in v1.3.
                val user = db.getUser(input)
                if (user == null || user.passwordHash != Session.hashPassword(input, password)) {
                    Toast.makeText(this, "Wrong username or password", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Session.save(this, user.username, user.role, user.donorId)
                Toast.makeText(this, "Welcome, ${user.username}", Toast.LENGTH_SHORT).show()
                goToMain()
                return@setOnClickListener
            }
            // (a) Supabase Auth login (email). Network on a background thread.
            val email = input
            Thread {
                val auth = if (SupabaseSync.isOnline) SupabaseAuth.signIn(email, password) else null
                val authError = SupabaseAuth.lastError
                if (auth != null) {
                    val local = db.getUser(email)
                    if (local != null) {
                        Session.saveAuth(
                            this, email, local.role, local.donorId,
                            email, auth.userId, auth.accessToken, auth.refreshToken
                        )
                        runOnUiThread {
                            Toast.makeText(this, "Welcome, $email", Toast.LENGTH_SHORT).show()
                            goToMain()
                        }
                    } else {
                        val donor = SupabaseAuth.getDonorByAuthId(auth.userId)
                        if (donor != null) {
                            db.insertDonorWithId(donor)
                            db.addUser(
                                UserAccount(
                                    email, Session.hashPassword(email, password),
                                    Session.ROLE_DONOR, donor.id
                                )
                            )
                            Session.saveAuth(
                                this, email, Session.ROLE_DONOR, donor.id,
                                email, auth.userId, auth.accessToken, auth.refreshToken
                            )
                            runOnUiThread {
                                Toast.makeText(this, "Welcome, $email", Toast.LENGTH_SHORT).show()
                                goToMain()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this, "No donor profile linked to this account",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } else {
                    // Sign-in failed. Offline fallback only when the
                    // failure is really the network, and the local row
                    // matches — never on a wrong password.
                    val networkFailed = !SupabaseSync.isOnline ||
                        authError == "No internet connection"
                    val local = if (networkFailed) db.getUser(email) else null
                    if (local != null &&
                        local.passwordHash == Session.hashPassword(email, password)
                    ) {
                        // Keep the previous Auth tokens for this same
                        // email if there are any, so online sync can
                        // still refresh once the network is back.
                        val prevAccess = Session.accessToken
                        val prevRefresh = Session.refreshToken
                        if (!prevAccess.isNullOrBlank() && !prevRefresh.isNullOrBlank() &&
                            Session.email == email
                        ) {
                            Session.saveAuth(
                                this, email, local.role, local.donorId,
                                email, Session.authUserId, prevAccess, prevRefresh
                            )
                        } else {
                            Session.save(this, local.username, local.role, local.donorId)
                        }
                        runOnUiThread {
                            Toast.makeText(this, "Signed in offline", Toast.LENGTH_SHORT).show()
                            goToMain()
                        }
                    } else {
                        val message = if (networkFailed) {
                            "Sign-in needs an internet connection"
                        } else {
                            authError ?: "Sign-in failed"
                        }
                        runOnUiThread {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
