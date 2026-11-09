package com.tayyab.blooddonation.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background update check, every 12 hours, whether or not the app is open.
 *
 * It only *checks* and, if something newer exists, posts a notification and
 * remembers the update. Downloading and installing always stay user-initiated:
 * an unattended install would restart the app under the user's hands.
 *
 * WorkManager's minimum periodic interval is 15 minutes and the OS batches
 * work, so "every 12 hours" means "not more often than that"; a device in Doze
 * can be late. That is fine — the app also checks on launch.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            when (val result = AppUpdater.checkForUpdate()) {
                is AppUpdater.CheckResult.UpdateAvailable -> {
                    AppUpdater.savePending(applicationContext, result.info)
                    UpdateNotifier.updateAvailable(applicationContext, result.info)
                }
                // Up to date, or the check failed (no network, GitHub down).
                // Either way there is nothing to tell the user, and retrying
                // immediately would only drain the battery.
                else -> Unit
            }
            AppUpdater.markChecked(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "app_update_check"

        /** Safe to call on every launch: KEEP leaves an existing schedule alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
