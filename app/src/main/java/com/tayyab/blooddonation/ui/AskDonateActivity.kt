package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.util.AiCoach
import com.tayyab.blooddonation.util.AiRules
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.ui.UISync

/**
 * "Can I donate?" helper. One honest disclaimer, a length-limited question box,
 * and a reply that is presented as general information — never as a permission
 * to donate.
 *
 * NO personal data is sent: only the sanitised question itself (see
 * AiCoachPayloadTest). Nobody's name, blood group or donation history is
 * shipped to a language model, and the answer is deliberately general, because
 * eligibility is decided by a blood bank's screening, not by a model.
 *
 * Every failure path names itself — a limit reached, a helper that is not set
 * up yet, or a helper that could not be reached — and every one of them ends by
 * pointing at the licensed blood bank, which is where the real answer lives.
 */
class AskDonateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_ask_donate)
        title = "Can I donate?"

        findViewById<Button>(R.id.btnAskVerify).setOnClickListener { ask() }
    }

    private fun ask() {
        val button = findViewById<Button>(R.id.btnAskVerify)
        val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
        val label = findViewById<android.widget.TextView>(R.id.syncLabel)
        val raw = findViewById<EditText>(R.id.etQuestion).text.toString()
        val question = AiRules.sanitizeQuestion(raw)
        if (question.isEmpty()) {
            Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isFinishing && !isDestroyed) {
            button.isEnabled = false
        }
        try {
            UISync.withSync(this, spinner, label, "Asking…") {
                // Only the sanitised question is sent — no name, no blood group,
                // no donation history. The helper answers general questions; a
                // licensed blood bank decides anything individual.
                val answer = AiCoach.answer(question)
                UISync.safePost(this) {
                    button.isEnabled = true
                    show(answer)
                }
            }
        } finally {
            if (!isFinishing && !isDestroyed) {
                button.isEnabled = true
            }
        }
    }

    private fun show(answer: AiCoach.Answer) {
        val view = findViewById<TextView>(R.id.tvAnswer)
        view.visibility = View.VISIBLE
        view.text = when (answer) {
            is AiCoach.Answer.Ok -> answer.text
            is AiCoach.Answer.Limited ->
                answer.message +
                    "\n\nFor anything about your health or eligibility, the licensed blood " +
                    "bank you plan to donate at decides after their screening."
            is AiCoach.Answer.NotSetUp ->
                answer.message +
                    "\n\nFor anything about your health or eligibility, ask the licensed " +
                    "blood bank or hospital — their screening decides."
            is AiCoach.Answer.Failed ->
                answer.message +
                    "\n\nFor anything about your health or eligibility, ask the licensed " +
                    "blood bank or hospital — their screening decides."
        }
    }
}
