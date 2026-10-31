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
import com.tayyab.blooddonation.util.Background
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

    private companion object {
        /**
         * Shown when the stored hash predates PBKDF2. It is deliberately not a
         * "wrong password" message: the password is fine, the hash is simply
         * unverifiable, and an online sign-in fixes it permanently.
         */
        const val LEGACY_PASSWORD_MESSAGE =
            "This account's saved password is in an older format. Sign in once with " +
                "your email and password while online — the app then upgrades it and " +
                "this login works again."

        const val LEGACY_OFFLINE_MESSAGE =
            "This phone's saved password is in an older format. Sign in once with an " +
                "internet connection to upgrade it."
    }

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
                // (b) Local-only login: the seeded demo manager, or an account
                // that has already signed in online once.
                val user = db.getUser(input)
                if (user == null) {
                    Toast.makeText(this, "Wrong username or password", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                when (Session.checkPassword(user.passwordHash, password)) {
                    Session.PasswordCheck.OK -> {
                        Session.save(this, user.username, user.role, user.donorId)
                        Toast.makeText(this, "Welcome, ${user.username}", Toast.LENGTH_SHORT).show()
                        goToMain()
                    }
                    Session.PasswordCheck.LEGACY_FORMAT -> {
                        Toast.makeText(this, LEGACY_PASSWORD_MESSAGE, Toast.LENGTH_LONG).show()
                    }
                    Session.PasswordCheck.WRONG -> {
                        Toast.makeText(this, "Wrong username or password", Toast.LENGTH_SHORT).show()
                    }
                }
                return@setOnClickListener
            }
            // (a) Supabase Auth login (email). Network on a background thread.
            val email = input
            Background.run {
                val auth = if (SupabaseSync.isOnline) SupabaseAuth.signIn(email, password) else null
                val authError = SupabaseAuth.lastError
                if (auth != null) {
                    val local = db.getUser(email)
                    if (local != null) {
                        // Supabase Auth just proved the password, so this is the
                        // one moment a stored hash from an older release (or a
                        // weaker PBKDF2 setting) can be rewritten safely.
                        if (Session.needsRehash(local.passwordHash)) {
                            db.updatePasswordHash(email, Session.hashPassword(password))
                        }
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
                                    email, Session.hashPassword(password),
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
                    val localCheck = if (local != null) {
                        Session.checkPassword(local.passwordHash, password)
                    } else {
                        Session.PasswordCheck.WRONG
                    }
                    if (local != null && localCheck == Session.PasswordCheck.OK) {
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
                        // A legacy hash cannot be checked offline, so say that
                        // rather than pretending the password was wrong.
                        val message = when {
                            localCheck == Session.PasswordCheck.LEGACY_FORMAT ->
                                LEGACY_OFFLINE_MESSAGE
                            networkFailed -> "Sign-in needs an internet connection"
                            else -> authError ?: "Sign-in failed"
                        }
                        runOnUiThread {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
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
