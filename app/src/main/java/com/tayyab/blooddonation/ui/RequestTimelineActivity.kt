package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.StatusEvent
import kotlinx.coroutines.launch

/**
 * Phase 2: the status history of one request, shown with the existing status
 * vocabulary. Built as the first ViewModel + coroutines screen: collection is
 * bound to STARTED with [repeatOnLifecycle], so a backgrounded or destroyed
 * screen stops observing and a rotation simply re-collects the same StateFlow.
 */
class RequestTimelineActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private var requestId: Long = -1
    private lateinit var viewModel: RequestTimelineViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_timeline)
        title = getString(R.string.timeline_title)
        db = DbHelper(this)
        requestId = intent.getLongExtra("requestId", -1)
        viewModel = ViewModelProvider(this)[RequestTimelineViewModel::class.java]

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.events.collect { render(it) } }
                launch {
                    viewModel.loading.collect { busy ->
                        findViewById<ProgressBar>(R.id.progressTimeline).visibility =
                            if (busy) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.message.collect { code ->
                        if (code != null) {
                            Toast.makeText(
                                this@RequestTimelineActivity,
                                if (code == RequestTimelineViewModel.MSG_OFFLINE) {
                                    R.string.timeline_offline
                                } else {
                                    R.string.timeline_error
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            viewModel.messageShown()
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) viewModel.load(db, requestId)
    }

    private fun render(events: List<StatusEvent>) {
        val tv = findViewById<TextView>(R.id.tvTimeline)
        if (events.isEmpty()) {
            tv.text = getString(R.string.timeline_empty)
            return
        }
        tv.text = events.joinToString("\n\n") { e ->
            getString(
                R.string.timeline_entry,
                statusLabel(e.status),
                displayDate(e.changedAt),
                actorLabel(e.actor)
            )
        }
    }

    private fun statusLabel(status: String): String = getString(
        when (status) {
            "Pending" -> R.string.timeline_status_pending
            "Matched" -> R.string.timeline_status_matched
            "Fulfilled" -> R.string.timeline_status_fulfilled
            "Cancelled" -> R.string.timeline_status_cancelled
            "Expired" -> R.string.timeline_status_expired
            else -> R.string.timeline_status_other
        }
    )

    private fun actorLabel(code: String?): String = getString(
        when (code) {
            "requester" -> R.string.timeline_actor_requester
            "manager" -> R.string.timeline_actor_manager
            "donor" -> R.string.timeline_actor_donor
            "server" -> R.string.timeline_actor_server
            else -> R.string.timeline_actor_unknown
        }
    )

    /** Supabase sends a timestamptz; local rows are plain yyyy-MM-dd. */
    private fun displayDate(raw: String): String =
        if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') raw.substring(0, 10) else raw
}
