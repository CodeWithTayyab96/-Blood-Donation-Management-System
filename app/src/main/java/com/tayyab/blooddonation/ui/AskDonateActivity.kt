package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.AiCoach
import com.tayyab.blooddonation.util.AiRules
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "Can I donate?" helper (Phase 4). One honest disclaimer, a length-limited
 * question box, and a reply that is presented as general information — never
 * as a permission to donate. The four neutral facts [AiCoach.Facts] are the
 * only personal data sent (see AiCoachPayloadTest); nobody's donor history is
 * shipped to a language model.
 *
 * Every failure path shows the same fallback text instead of pretending the
 * AI said something: when the helper is offline, not configured, rate-limited
 * or simply unreachable, a licensed blood bank is the right place to ask.
 */
class AskDonateActivity : AppCompatActivity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_ask_donate)
        title = "Can I donate?"
        db = DbHelper(this)

        findViewById<Button>(R.id.btnAskVerify).setOnClickListener { ask() }
    }

    private fun ask() {
        val button = findViewById<Button>(R.id.btnAskVerify)
        val raw = findViewById<EditText>(R.id.etQuestion).text.toString()
        val question = AiRules.sanitizeQuestion(raw)
        if (question.isEmpty()) {
            Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
            return
        }
        button.isEnabled = false

        Background.run {
            val reply = AiCoach.answer(factsFor(), question)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                button.isEnabled = true
                show(reply)
            }
        }
    }

    /** The four neutral facts, exactly as they will travel (and no more). */
    private fun factsFor(): AiCoach.Facts {
        val donor = Session.donorId?.let { db.getDonor(it) }
        val name = (donor?.name ?: Session.username ?: "friend").substringBefore(' ')
        val days = donor?.lastDonationDate?.let {
            try {
                ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now())
            } catch (_: Exception) {
                null
            }
        }
        return AiCoach.Facts(
            firstName = name.ifBlank { "friend" },
            donationCount = donor?.id?.let { db.donationsForDonor(it).size } ?: 0,
            daysSinceLastDonation = days,
            bloodGroup = donor?.bloodGroup ?: ""
        )
    }

    private fun show(reply: String?) {
        val answer = findViewById<TextView>(R.id.tvAnswer)
        answer.visibility = View.VISIBLE
        answer.text = reply
            ?: "I could not reach the helper just now. For anything about your health, " +
            "medicines or eligibility, ask the licensed blood bank or hospital you " +
            "plan to donate at — their screening decides."
    }
}
