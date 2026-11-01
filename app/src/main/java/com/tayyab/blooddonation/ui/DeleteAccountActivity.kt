package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.AccountDeletion
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session

/**
 * "Delete my account" — the confirmation screen, and the only place the app is
 * allowed to destroy an account.
 *
 * The account itself is deleted by the `delete-account` Edge Function, because
 * deleting an Auth user needs the service role key and that key must never be
 * on a phone. This screen asks the user to confirm, calls it, and then decides
 * what to wipe locally based on what actually happened:
 *
 *  - deleted  — wipe the local database and log out. Keeping the cache would
 *    leave the phone showing a donor, requests and donations that no longer
 *    exist anywhere.
 *  - partial  — the personal data is gone but the login survived. Nothing is
 *    wiped and the user stays signed in, because pressing the button again
 *    finishes the job and logging them out would strand them.
 *  - refused  — nothing happened, so nothing is wiped.
 */
class DeleteAccountActivity : AppCompatActivity() {

    private lateinit var tvReason: TextView
    private lateinit var etConfirm: EditText
    private lateinit var btnDelete: Button

    private var inFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delete_account)

        tvReason = findViewById(R.id.tvReason)
        etConfirm = findViewById(R.id.etConfirm)
        btnDelete = findViewById(R.id.btnDelete)

        findViewById<TextView>(R.id.tvDeletedList).text = DELETED.joinToString("\n") { "• $it" }
        findViewById<TextView>(R.id.tvKeptList).text = KEPT.joinToString("\n") { "• $it" }

        btnDelete.setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    private fun confirmDelete() {
        if (inFlight) return
        if (etConfirm.text.toString().trim() != "DELETE") {
            tvReason.text = "Type DELETE in the box above, in capital letters."
            return
        }
        // The last question asked face to face, not by a message buried in a
        // list. Deleting the account is the one action in the app that cannot
        // be undone by any means, including by the project owner.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete your account?")
            .setMessage(
                "Your profile, contact number and open requests are deleted now " +
                    "and cannot be recovered."
            )
            .setPositiveButton("Delete") { _, _ -> runDelete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runDelete() {
        inFlight = true
        btnDelete.isEnabled = false
        btnDelete.text = "Deleting…"
        tvReason.text = "Contacting the server…"

        Background.run {
            val result = AccountDeletion.delete()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                inFlight = false
                btnDelete.isEnabled = true
                btnDelete.text = "Delete my account"
                when (result) {
                    is AccountDeletion.Result.Deleted -> finishDeletion()
                    is AccountDeletion.Result.Partial -> {
                        tvReason.text =
                            "${result.reason}\n\nYour account still works. Press the button " +
                            "again to finish — it is safe to retry."
                    }
                    is AccountDeletion.Result.Refused -> {
                        tvReason.text = "${result.reason}\n\nNothing was deleted."
                    }
                }
            }
        }
    }

    /**
     * The account is gone on the server, so the phone stops pretending it
     * exists: empty the local database, drop the session, and go back to the
     * login screen.
     */
    private fun finishDeletion() {
        Background.run {
            runCatching { DbHelper(this@DeleteAccountActivity).wipeLocalData() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Session.logout(this@DeleteAccountActivity)
                // The login screen, with nothing behind it: the back stack is
                // cleared so Back cannot walk into an app with no account.
                val intent = Intent(this@DeleteAccountActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    private companion object {
        val DELETED = listOf(
            "your profile: name, age, blood group, city, weight, phone number",
            "your saved contact number",
            "your device token, so notifications stop",
            "your offers to donate",
            "your open blood requests",
            "your sign-in — the account stops existing"
        )
        val KEPT = listOf(
            "your donation history, detached from your name — the blood bank " +
                "must keep its own record of donations",
            "requests that were already fulfilled, without your name or number",
            "the stock levels, which belong to the blood bank, not to you"
        )
    }
}
