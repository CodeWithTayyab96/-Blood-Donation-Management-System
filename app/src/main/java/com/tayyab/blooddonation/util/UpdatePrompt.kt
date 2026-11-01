package com.tayyab.blooddonation.util

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import com.tayyab.blooddonation.BuildConfig

/**
 * The update UI, in one place because two different home screens use it: the
 * manager dashboard and the ordinary user's home.
 *
 * Everything here runs on the UI thread except the check and the download,
 * which are handed to [Background]; [AppUpdater] never touches the UI itself.
 */
object UpdatePrompt {

    /**
     * Called from a home screen's onCreate.
     *
     *  - an update the background worker already found is offered immediately;
     *  - [fromNotification] forces a check (the user tapped the notification);
     *  - otherwise it checks at most once every 12 hours, quietly.
     */
    fun checkOnLaunch(activity: Activity, fromNotification: Boolean) {
        val pending = AppUpdater.takePending(activity)
        if (pending != null) {
            show(activity, pending)
            return
        }
        if (!fromNotification && !AppUpdater.shouldCheckNow(activity)) return

        Background.run {
            val result = AppUpdater.checkForUpdate()
            AppUpdater.markChecked(activity)
            if (result is AppUpdater.CheckResult.UpdateAvailable) {
                AppUpdater.savePending(activity, result.info)
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        show(activity, result.info)
                    }
                }
            }
        }
    }

    fun show(activity: Activity, info: AppUpdater.UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val notes = if (info.notes.isBlank()) {
            "A new version is available."
        } else {
            info.notes.take(500)
        }
        AlertDialog.Builder(activity)
            .setTitle("Update available: v${info.versionName}")
            .setMessage(
                "You have v${BuildConfig.VERSION_NAME}.\n\n$notes\n\n" +
                    "The new version is downloaded from GitHub Releases and installed " +
                    "here. Your donor data stays on this phone."
            )
            .setPositiveButton("Download and install") { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    fun startDownload(activity: Activity, info: AppUpdater.UpdateInfo) {
        if (!AppUpdater.canInstallPackages(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("Allow installing updates")
                .setMessage(
                    "Android needs your permission before this app can install its own " +
                        "updates. Turn on \"Allow from this source\" on the next screen, " +
                        "then try again."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    AppUpdater.openInstallPermissionScreen(activity)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        // A plain non-cancellable dialog showing the percentage, instead of the
        // deprecated android.app.ProgressDialog (which renders unstyled under a
        // Material theme and is removed in newer SDKs).
        val progress = AlertDialog.Builder(activity)
            .setTitle("Downloading update")
            .setMessage("Downloading v${info.versionName}...")
            .setCancelable(false)
            .create()
        progress.show()

        Background.run {
            AppUpdater.downloadAndInstall(
                activity,
                info,
                onProgress = { percent ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            progress.setMessage("Downloading v${info.versionName}... $percent%")
                        }
                    }
                },
                onError = { message ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) progress.dismiss()
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    }
                }
            )
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) progress.dismiss()
            }
        }
    }
}
