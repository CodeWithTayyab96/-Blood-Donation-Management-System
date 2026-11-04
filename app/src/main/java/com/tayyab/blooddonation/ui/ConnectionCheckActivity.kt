package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Jwt
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseClient

/**
 * Settings -> Connection check.
 *
 * Five questions, in the order they can fail, each with the reason rather than
 * a generic "offline":
 *
 *   1. is this build configured at all (URL + anon key)?
 *   2. does the server answer?
 *   3. is there a session, and is its token still valid?
 *   4. does a real table read work with that session?
 *   5. do the two database helper functions answer?
 *
 * The first failure is almost always the whole story: a 401 at step 4 with a
 * valid session means the policies, not the network.
 */
class ConnectionCheckActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_connection_check)
        title = "Connection check"
        findViewById<Button>(R.id.btnRunChecks).setOnClickListener { runChecks() }
        runChecks()
    }

    private fun runChecks() {
        val out = findViewById<TextView>(R.id.tvChecks)
        val button = findViewById<Button>(R.id.btnRunChecks)
        val spinner = findViewById<ProgressBar>(R.id.progressSync)
        val label = findViewById<TextView>(R.id.syncLabel)
        out.text = "Checking…"
        button.isEnabled = false
        spinner.visibility = View.VISIBLE
        label.visibility = View.VISIBLE
        label.text = "Running checks…"

        Background.run {
            // Sequential on purpose: each check uses what the previous one
            // proved, and the first failure is the interesting one.
            val lines = listOf(
                line("1. Configuration", SupabaseClient.probeConfig()),
                line("2. Server", SupabaseClient.probeServer()),
                line("3. Session", sessionProbe()),
                line("4. A read works", SupabaseClient.probeRead()),
                line("5. Helper functions", SupabaseClient.probeHelperFunctions())
            )
            val text = SpannableStringBuilder()
            lines.forEachIndexed { index, check ->
                if (index > 0) text.append('\n')
                text.append(check)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                out.text = text
                button.isEnabled = true
                spinner.visibility = View.GONE
                label.visibility = View.GONE
            }
        }
    }

    /**
     * One check line. The status code (check 2) is appended in a smaller size:
     * a 401 there means the server answered, not that anything failed.
     */
    private fun line(label: String, probe: SupabaseClient.Probe): CharSequence {
        val text = SpannableStringBuilder()
            .append(if (probe.ok) "✓ " else "✗ ")
            .append(label)
            .append(" — ")
            .append(probe.detail)
        val note = probe.note
        if (!note.isNullOrBlank()) {
            val start = text.length
            text.append("  (").append(note).append(")")
            text.setSpan(
                RelativeSizeSpan(0.78f),
                start,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return text
    }

    private fun sessionProbe(): SupabaseClient.Probe {
        val token = Session.accessToken
        if (token.isNullOrBlank()) {
            return SupabaseClient.Probe(
                false,
                "not signed in with an email account — cached data only"
            )
        }
        val expiresAt = Jwt.expiresAtMs(token)
            ?: return SupabaseClient.Probe(true, "token present (no expiry claim)")
        val minutes = (expiresAt - System.currentTimeMillis()) / 60_000
        return if (minutes > 0) {
            SupabaseClient.Probe(true, "token valid for another $minutes min")
        } else {
            SupabaseClient.Probe(
                false,
                "token expired ${-minutes} min ago — sign in again"
            )
        }
    }
}
