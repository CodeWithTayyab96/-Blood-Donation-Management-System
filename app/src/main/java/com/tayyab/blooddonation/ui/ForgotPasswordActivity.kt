package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.SupabaseAuth

/**
 * Password recovery, as Supabase's code flow: the app asks for a 6-digit code
 * by email, the user types it back, and the code is exchanged for a session —
 * because only a session may set a new password.
 *
 * The screen has three states but stays on one Activity: the address typed in
 * step one is the same one the code is verified against in step two, and a
 * second Activity would have to pass it along and could get it wrong.
 */
class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var stepEmail: LinearLayout
    private lateinit var stepCode: LinearLayout
    private lateinit var etEmail: EditText
    private lateinit var etCode: EditText
    private lateinit var etNewPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSendCode: Button
    private lateinit var btnSetPassword: Button
    private lateinit var btnResend: Button

    /** The address the code was sent to. Step two never re-reads the field. */
    private var sentTo: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        tvStatus = findViewById(R.id.tvStatus)
        stepEmail = findViewById(R.id.stepEmail)
        stepCode = findViewById(R.id.stepCode)
        etEmail = findViewById(R.id.etEmail)
        etCode = findViewById(R.id.etCode)
        etNewPassword = findViewById(R.id.etNewPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnSendCode = findViewById(R.id.btnSendCode)
        btnSetPassword = findViewById(R.id.btnSetPassword)
        btnResend = findViewById(R.id.btnResend)

        btnSendCode.setOnClickListener { sendCode() }
        btnResend.setOnClickListener { resendCode() }
        btnSetPassword.setOnClickListener { setNewPassword() }
        findViewById<Button>(R.id.btnBackToLogin).setOnClickListener { finish() }
    }

    // ------------------------------------------------------------------ step 1

    private fun sendCode() {
        val email = etEmail.text.toString().trim()
        if (!email.contains("@")) {
            Toast.makeText(this, "Enter the email address of your account.", Toast.LENGTH_SHORT).show()
            return
        }
        busy(true, btnSendCode)
        Background.run {
            val ok = SupabaseAuth.recover(email)
            val error = SupabaseAuth.lastError
            runOnUiThread {
                busy(false, btnSendCode)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) {
                    tvStatus.text = error ?: "Could not send the email."
                    return@runOnUiThread
                }
                sentTo = email
                // Accepted, not necessarily delivered: GoTrue answers 200 even
                // when it decides not to send, so that addresses cannot be
                // guessed. The wording has to say that.
                tvStatus.text =
                    "If that address is registered, a 6-digit code is on its way to $email. It expires, so use it soon."
                stepEmail.visibility = View.GONE
                stepCode.visibility = View.VISIBLE
            }
        }
    }

    private fun resendCode() {
        if (sentTo.isBlank()) return
        busy(true, btnResend)
        Background.run {
            val ok = SupabaseAuth.recover(sentTo)
            val error = SupabaseAuth.lastError
            runOnUiThread {
                busy(false, btnResend)
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvStatus.text = if (ok) {
                    "A new code is on its way to $sentTo. The old one will not work any more."
                } else {
                    error ?: "Could not send the email."
                }
            }
        }
    }

    // ------------------------------------------------------------------ step 2

    private fun setNewPassword() {
        val code = etCode.text.toString().trim()
        val first = etNewPassword.text.toString()
        val second = etConfirmPassword.text.toString()

        when {
            code.length != 6 ->
                Toast.makeText(this, "The code is 6 digits.", Toast.LENGTH_SHORT).show()
            first.length < MIN_PASSWORD_LENGTH ->
                Toast.makeText(
                    this,
                    "Use at least $MIN_PASSWORD_LENGTH characters for the password.",
                    Toast.LENGTH_SHORT
                ).show()
            first != second ->
                Toast.makeText(this, "The two passwords do not match.", Toast.LENGTH_SHORT).show()
            else -> verifyThenSet(code, first)
        }
    }

    /**
     * Two calls, in this order: the code buys a session, and the session is what
     * is allowed to change the password. There is no way to skip the first one —
     * an anonymous caller cannot set anyone's password.
     */
    private fun verifyThenSet(code: String, newPassword: String) {
        busy(true, btnSetPassword)
        Background.run {
            val session = SupabaseAuth.verifyRecovery(sentTo, code)
            val verifyError = SupabaseAuth.lastError
            val ok = session != null &&
                SupabaseAuth.updatePassword(session.accessToken, newPassword)
            val updateError = SupabaseAuth.lastError
            runOnUiThread {
                busy(false, btnSetPassword)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) {
                    tvStatus.text = when {
                        session == null -> verifyError ?: "That code did not work."
                        else -> updateError ?: "Could not set the new password."
                    }
                    return@runOnUiThread
                }
                Toast.makeText(this, "Password updated. Sign in with it.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Disables the button while a call is in flight so it cannot be double-tapped. */
    private fun busy(isBusy: Boolean, button: Button) {
        button.isEnabled = !isBusy
        button.text = if (isBusy) {
            "Working…"
        } else when (button.id) {
            R.id.btnSendCode -> "Send me a code"
            R.id.btnResend -> "Send the code again"
            R.id.btnSetPassword -> "Set a new password"
            else -> button.text.toString()
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}
