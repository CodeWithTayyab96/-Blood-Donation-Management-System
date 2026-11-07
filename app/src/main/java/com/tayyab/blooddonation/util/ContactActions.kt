package com.tayyab.blooddonation.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.tayyab.blooddonation.R

/**
 * The small amount of Android plumbing behind the Call / WhatsApp / Share
 * buttons. All number *rules* live in [PhoneNumber] and all share *text* in
 * [ShareText], both pure; this object only turns them into intents and makes
 * sure a missing app shows a message instead of crashing.
 *
 * ACTION_DIAL never needs the CALL_PHONE permission: it opens the dialler with
 * the number filled in and the user decides whether to place the call.
 */
object ContactActions {

    /** WhatsApp and WhatsApp Business, declared as <queries> in the manifest
     *  so Android 11+ package visibility lets us look them up. */
    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    private fun installedWhatsApp(context: Context): String? =
        WHATSAPP_PACKAGES.firstOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    /**
     * Shows the row and wires both buttons when [rawPhone] is usable; hides the
     * whole row otherwise. Call this on every render, because a sync can arrive
     * with a number the account is not allowed to see (or with none at all).
     */
    fun bind(
        context: Context,
        row: View,
        callButton: Button,
        whatsAppButton: Button,
        rawPhone: String?
    ) {
        val number = PhoneNumber.normalize(rawPhone)
        if (number == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        callButton.setOnClickListener { dial(context, number) }
        whatsAppButton.setOnClickListener { whatsApp(context, number) }
    }

    fun dial(context: Context, e164: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse(PhoneNumber.dialUri(e164)))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.msg_no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

    fun whatsApp(context: Context, e164: String) {
        val pkg = installedWhatsApp(context)
        if (pkg == null) {
            Toast.makeText(context, R.string.msg_whatsapp_not_installed, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PhoneNumber.waMeUrl(e164))).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The package exists but does not handle wa.me links (a very old
            // build). Fall back to any browser rather than a dead button.
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse(PhoneNumber.waMeUrl(e164)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(browser)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.msg_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** ACTION_SEND chooser; all wording comes from strings.xml via [ShareText]. */
    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.share_chooser_title))
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.msg_no_share_app, Toast.LENGTH_SHORT).show()
        }
    }
}
