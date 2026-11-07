package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.util.AiCoach
import com.tayyab.blooddonation.util.AiRules
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.ui.UISync

/**
 * "Can I donate?" helper, chat view since v1.22. One honest disclaimer, a
 * length-limited question box, and replies that are presented as general
 * information — never as a permission to donate.
 *
 * NO personal data is sent: only the sanitised question itself (see
 * AiCoachPayloadTest). Nobody's name, blood group or donation history is
 * shipped to a language model, and the answer is deliberately general, because
 * eligibility is decided by a blood bank's screening, not by a model.
 *
 * Every failure path names itself — a limit reached, a helper that is not set
 * up yet, or a helper that could not be reached — and every one of them ends by
 * pointing at the licensed blood bank, which is where the real answer lives.
 *
 * The conversation lives only for this visit; nothing is stored.
 */
class AskDonateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_ask_donate)
        title = "AI Helper"

        addMessage(getString(R.string.chat_greeting), mine = false)

        val questionBox = findViewById<EditText>(R.id.etQuestion)
        findViewById<Button>(R.id.chipEligibility).setOnClickListener {
            questionBox.setText("Am I eligible to donate blood?")
        }
        findViewById<Button>(R.id.chipOften).setOnClickListener {
            questionBox.setText("How often can I donate blood?")
        }
        findViewById<Button>(R.id.chipEat).setOnClickListener {
            questionBox.setText("What should I eat before donating blood?")
        }
        findViewById<Button>(R.id.btnAskVerify).setOnClickListener { ask() }
    }

    private fun ask() {
        val button = findViewById<Button>(R.id.btnAskVerify)
        val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
        val label = findViewById<android.widget.TextView>(R.id.syncLabel)
        val questionBox = findViewById<EditText>(R.id.etQuestion)
        val question = AiRules.sanitizeQuestion(questionBox.text.toString())
        if (question.isEmpty()) {
            Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(question, mine = true)
        questionBox.setText("")
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
                    addMessage(answerText(answer), mine = false)
                }
            }
        } finally {
            if (!isFinishing && !isDestroyed) {
                button.isEnabled = true
            }
        }
    }

    /** The answer as plain text, with the blood-bank pointer every failure
     *  path carries. */
    private fun answerText(answer: AiCoach.Answer): String = when (answer) {
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

    /** One bubble in the conversation: the user's own on the right (rose),
     *  the helper's on the left (white card). */
    private fun addMessage(text: String, mine: Boolean) {
        val density = resources.displayMetrics.density
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(getColor(R.color.on_surface))
            setPadding(
                (14 * density).toInt(), (10 * density).toInt(),
                (14 * density).toInt(), (10 * density).toInt()
            )
            background = ContextCompat.getDrawable(
                this@AskDonateActivity,
                if (mine) R.drawable.bg_pill_rose else R.drawable.bg_input
            )
            maxWidth = (280 * density).toInt()
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = if (mine) Gravity.END else Gravity.START
        params.topMargin = (8 * density).toInt()
        bubble.layoutParams = params
        findViewById<LinearLayout>(R.id.messagesContainer).addView(bubble)
        val scroll = findViewById<ScrollView>(R.id.scrollMessages)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
