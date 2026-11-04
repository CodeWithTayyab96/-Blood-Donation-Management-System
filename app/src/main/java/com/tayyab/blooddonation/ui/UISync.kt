package com.tayyab.blooddonation.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.tayyab.blooddonation.util.Background

/**
 * Shared, light-weight loading UI for the blood-donation app.
 *
 * The app's existing pattern is: show the local copy immediately, run network
 * work on [Background], and post a Toast when it finishes. This helper adds a
 * small, consistent "in-flight" indicator on top of that — never a full-screen
 * blocking dialog for normal syncs, and never a spinner that lives longer than
 * the work.
 *
 * Two primitives:
 *  - [showSync] / [hideSync]: a small ProgressBar + label, used while a
 *    background pull or write is running. The caller provides the two views
 *    (often created once in the layout).
 *  - [pauseUntil]: a short-lived full-screen overlay shown while [predicate]
 *    is false (e.g. cold start, waiting for Session.load / a one-shot action),
 *    removed as soon as it becomes true. It dims the window and blocks touches
 *    but the Activity's own code still runs.
 *
 * Loading-UI philosophy (kept consistent across screens):
 *  - Show the cached screen first; run network work on a background thread;
 *    report the result with a Toast.
 *  - Add the small [showSync] indicator when a background pull or write is
 *    running and the user might otherwise think the app had frozen.
 *  - Use [pauseUntil] only for short, unavoidable waits where *nothing* can be
 *    shown yet (cold start, one-shot waits), never for a normal sync.
 *  - Do not block the whole screen for normal data pulls or writes.
 */

object UISync {

    /** Show the small in-screen sync indicator. Call on the UI thread or via
     *  the activity's runOnUiThread; safe when the activity is finishing. */
    fun showSync(activity: Activity, progress: ProgressBar, label: TextView, text: String) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                label.apply {
                    visibility = View.VISIBLE
                    text = text
                }
            }
        }
    }

    /** Hide the small in-screen sync indicator. */
    fun hideSync(activity: Activity, progress: ProgressBar, label: TextView) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                progress.visibility = View.GONE
                label.visibility = View.GONE
            }
        }
    }

    /** Run [work] on [Background], showing the sync indicator while it runs and
     *  hiding it when it ends (even on failure). `activity` is only used to
     *  post the show/hide calls to the main thread; the work block runs on a
     *  background thread and may call `activity.runOnUiThread { ... }` to
     *  update the UI.
     *
     *  The work block is a plain `() -> Unit` (not suspend): it runs on the
     *  shared [Background] executor, which is non-suspending. Callers that need
     *  to call a suspend function should wrap it in their own coroutine — but
     *  every current caller calls only blocking network helpers (SupabaseSync,
     *  AiCoach) plus `runOnUiThread`, so no suspend is needed. */
    fun withSync(
        activity: Activity,
        progress: ProgressBar,
        syncLabel: TextView,
        labelText: String,
        work: () -> Unit = {}
    ) {
        showSync(activity, progress, syncLabel, labelText)
        Background.run {
            try {
                work()
            } finally {
                hideSync(activity, progress, syncLabel)
            }
        }
    }

    /** Full-screen dimmed overlay with a centered ProgressBar + message, shown
     *  until [predicate] returns true (polled every [pollMs] on the main thread),
     *  then removed. The overlay blocks touches but the Activity's code still
     *  runs, so this is for short waits (cold start, one-shot actions), not for
     *  indefinite polling.
     *
     *  Set [acceptFinishing] to true when the condition you are waiting for
     *  (e.g. background data pull) might continue after the Activity is no
     *  longer visible; otherwise the overlay is removed as soon as the Activity
     *  finishes (preventing it from lingering over the next screen). */
    fun pauseUntil(
        activity: Activity,
        predicate: () -> Boolean,
        message: String = "Loading…",
        pollMs: Long = 30,
        acceptFinishing: Boolean = false
    ) {
        val container = android.widget.FrameLayout(activity).apply {
            id = android.view.View.generateViewId()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.argb(0xCC, 0, 0, 0))
        }
        val progress = ProgressBar(activity).apply {
            id = android.view.View.generateViewId()
            isIndeterminate = true
            style = android.R.attr.progressBarStyleLarge
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.Gravity.CENTER
            )
        }
        val messageView = TextView(activity).apply {
            id = android.view.View.generateViewId()
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.Gravity.CENTER
            )
        }
        container.addView(progress)
        container.addView(messageView)

        val wrapper = activity.findViewById(android.R.id.content)
        wrapper.addView(container)

        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!acceptFinishing && (activity.isFinishing || activity.isDestroyed)) {
                    container.visibility = View.GONE
                    return
                }
                if (predicate()) {
                    container.visibility = View.GONE
                    return
                }
                handler.postDelayed(this, pollMs)
            }
        }
        handler.post(runnable)
    }
}
