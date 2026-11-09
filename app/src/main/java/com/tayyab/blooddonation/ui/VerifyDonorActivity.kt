package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.Verification
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync
import com.tayyab.blooddonation.util.VerificationRules
import java.time.LocalDate

/**
 * Phase 3: a manager sets a donor's verification (approve / reject). The donor's
 * latest record is what [VerificationRules.isVerified] reads, so a later reject
 * or a fresh pending submission clears a previously verified badge.
 */
class VerifyDonorActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private var donorId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        if (!Session.isManager) {
            finish()
            return
        }
        setContentView(R.layout.activity_verify_donor)
        title = getString(R.string.title_verify)
        db = DbHelper(this)
        donorId = intent.getLongExtra("donorId", -1)

        findViewById<TextView>(R.id.tvVerifyName).text = db.getDonor(donorId)?.name ?: getString(R.string.title_verify)
        renderStatus()

        val etNote = findViewById<EditText>(R.id.etVerifyNote)
        val actor = Session.label()

        findViewById<Button>(R.id.btnApprove).setOnClickListener {
            save(VerificationRules.VERIFIED, etNote.text.toString().trim(), actor)
        }
        findViewById<Button>(R.id.btnReject).setOnClickListener {
            save(VerificationRules.REJECTED, etNote.text.toString().trim(), actor)
        }
    }

    private fun renderStatus() {
        val records = db.getVerifications(donorId)
        val status = if (VerificationRules.isVerified(records)) {
            getString(R.string.verify_verified)
        } else {
            records.lastOrNull()?.status?.let { labelFor(it) } ?: getString(R.string.verify_not_verified)
        }
        findViewById<TextView>(R.id.tvVerifyStatus).text =
            getString(R.string.verify_status_label, status)
    }

    private fun labelFor(status: String): String = when (status) {
        VerificationRules.VERIFIED -> getString(R.string.verify_verified)
        VerificationRules.REJECTED -> getString(R.string.verify_rejected)
        else -> getString(R.string.verify_pending)
    }

    private fun save(status: String, note: String, actor: String) {
        val verification = Verification(
            donorId = donorId,
            status = status,
            verifiedBy = actor,
            verifiedAt = LocalDate.now().toString(),
            note = note
        )
        if (SupabaseSync.isOnline) {
            // Online first so the record keeps its server id locally
            // too; if Supabase is unreachable, save locally only and
            // say so.
            Background.run {
                val remoteId = SupabaseSync.insertVerificationRemote(verification)
                if (remoteId != null) db.upsertVerification(verification.copy(id = remoteId))
                else db.insertVerification(verification)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (remoteId != null) R.string.verify_saved
                        else R.string.verify_saved_phone_only,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        } else {
            db.insertVerification(verification)
            Toast.makeText(this, R.string.verify_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
