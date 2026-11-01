package com.tayyab.blooddonation.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the result of the PackageInstaller session started by
 * [AppUpdater.installWithPackageInstaller].
 *
 * Three outcomes matter:
 *  - [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system still wants a
 *    tap (typical on Android 8-11, and on 12+ if "install unknown apps" was
 *    granted only for this one install). Its own confirmation intent is
 *    attached; we start it and nothing else happens.
 *  - [PackageInstaller.STATUS_SUCCESS]: the app was replaced and will restart.
 *  - anything else: a readable notification with the system's message.
 *
 * Declared in the manifest with `android:exported="false"`: only this app (and
 * the system, which sends the result) can reach it.
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    UpdateNotifier.failed(context, "Android asked for confirmation but sent no prompt.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateNotifier.installed(context)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateNotifier.failed(
                    context,
                    message?.takeIf { it.isNotBlank() } ?: "Install failed (status $status)."
                )
            }
        }
    }
}
