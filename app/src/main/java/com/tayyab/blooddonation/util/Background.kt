package com.tayyab.blooddonation.util

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's background thread pool.
 *
 * A small fixed pool (3 threads) so independent I/O-bound work — Supabase
 * pulls, DB reads, APK downloads — can overlap instead of queuing behind
 * each other. SQLiteOpenHelper handles its own locking, so concurrent
 * writers are safe.
 *
 * Callers must still post UI updates back with `runOnUiThread`; nothing here
 * touches the main thread. A task must not throw: the Supabase clients and
 * [SupabaseSync] are written never to, and an escaping exception would crash
 * the process exactly as it did with a bare Thread.
 */
object Background {

    private val counter = AtomicInteger()

    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "blood-bg-${counter.getAndIncrement()}").apply { isDaemon = true }
    }

    /** Queues [task] on the shared pool. Safe to call from the UI thread. */
    fun run(task: () -> Unit) {
        executor.execute(task)
    }

    /** Alias for [run], for call sites where the I/O intent is worth naming. */
    fun runIo(task: () -> Unit) {
        executor.execute(task)
    }
}
