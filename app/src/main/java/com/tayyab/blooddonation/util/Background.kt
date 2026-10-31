package com.tayyab.blooddonation.util

import java.util.concurrent.Executors

/**
 * The app's single background worker.
 *
 * Every network call, every Supabase pull and every multi-step write used to
 * spawn its own `Thread`. Nothing cancelled or coalesced them, so tapping
 * between screens quickly stacked up several pulls all writing to SQLite at
 * once. This is one serial queue instead: work runs in the order it was
 * submitted, and a screen can be left or re-entered without leaking a thread.
 *
 * It is deliberately single-threaded. The work here is I/O-bound and short,
 * and serialising it means the local SQLite copy is never written by two
 * threads at the same moment. The one long task — downloading an update APK —
 * is already behind a non-cancellable progress dialog, so queuing behind it
 * is not a problem the user can notice.
 *
 * Callers must still post UI updates back with `runOnUiThread`; nothing here
 * touches the main thread. A task must not throw: the Supabase clients and
 * [SupabaseSync] are written never to, and an escaping exception would crash
 * the process exactly as it did with a bare Thread.
 */
object Background {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "blood-donation-bg").apply { isDaemon = true }
    }

    /** Queues [task] on the shared worker. Safe to call from the UI thread. */
    fun run(task: () -> Unit) {
        executor.execute(task)
    }
}
