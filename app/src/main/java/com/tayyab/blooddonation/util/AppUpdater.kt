package com.tayyab.blooddonation.util

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tayyab.blooddonation.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * In-app updater.
 *
 * How it works, in plain terms:
 * 1. Ask GitHub for the latest release of this repo.
 * 2. If that release ships an update.json, read its versionCode from there.
 *    Otherwise fall back to comparing the release tag (v1.2) with the
 *    installed version name.
 * 3. If the remote version is newer, download its APK asset into the app
 *    cache and hand it to Android's package installer.
 *
 * Security notes:
 * - The APK is only ever downloaded from this repo's own GitHub Releases.
 *   No other host is accepted, and the URL must end in .apk.
 * - Android itself verifies the signature: an update signed with a
 *   different key than the installed app is rejected by the system, so
 *   a stranger cannot push a fake update over this app. That is also why
 *   the release signing key (see .github/workflows/generate-key.yml) must
 *   never change once the first signed release ships.
 * - Downloading happens on a background thread, never on the UI thread.
 */
object AppUpdater {

    const val REPO_OWNER = "CodeWithTayyab96"
    const val REPO_NAME = "-Blood-Donation-Management-System"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val tag: String,
        val apkUrl: String,
        val notes: String
    )

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /** Run on a background thread. Returns the check result. */
    fun checkForUpdate(): CheckResult {
        return try {
            val release = JSONObject(getUrl(LATEST_RELEASE_URL))
            val tag = release.optString("tag_name", "")
            val notes = release.optString("body", "")
            val assets = release.optJSONArray("assets")
            var apkUrl: String? = null
            var updateJsonUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name == "app-release.apk") apkUrl = url
                    if (name == "update.json") updateJsonUrl = url
                }
                if (apkUrl == null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val url = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = url
                            break
                        }
                    }
                }
            }
            if (apkUrl.isNullOrBlank()) {
                return CheckResult.Error("Latest release has no APK file.")
            }
            // update.json (shipped by the release workflow) is the only
            // trustworthy source for the remote versionCode.
            var remoteCode: Int? = null
            var remoteName = tag.removePrefix("v")
            if (!updateJsonUrl.isNullOrBlank()) {
                try {
                    val info = JSONObject(getUrl(updateJsonUrl))
                    remoteCode = info.optInt("versionCode", -1).takeIf { it > 0 }
                    if (info.optString("versionName", "").isNotBlank()) {
                        remoteName = info.optString("versionName")
                    }
                } catch (_: Exception) {
                    // Fall through to tag comparison below.
                }
            }
            val isNewer = if (remoteCode != null) {
                remoteCode > BuildConfig.VERSION_CODE
            } else {
                compareVersionNames(remoteName, BuildConfig.VERSION_NAME) > 0
            }
            if (isNewer) {
                CheckResult.UpdateAvailable(
                    UpdateInfo(
                        versionName = remoteName,
                        versionCode = remoteCode ?: -1,
                        tag = tag,
                        apkUrl = apkUrl,
                        notes = notes
                    )
                )
            } else {
                CheckResult.UpToDate
            }
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "Update check failed.")
        }
    }

    /** Download the APK and open the system installer. Call from a background thread. */
    fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (percent: Int) -> Unit,
        onError: (message: String) -> Unit
    ) {
        try {
            val url = URL(info.apkUrl)
            // Only this repo's GitHub Releases host is trusted.
            if (url.host != "github.com" && url.host != "objects.githubusercontent.com") {
                onError("Update link is not from GitHub Releases.")
                return
            }
            if (!info.apkUrl.endsWith(".apk")) {
                onError("Update file is not an APK.")
                return
            }
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "app-update.apk")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "BloodDonation-Updater")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                onError("Download failed (HTTP ${connection.responseCode}).")
                return
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            // Never hand an unverified file to the installer: check that it is
            // this app, signed with the same key, and newer than what is
            // installed. Android would reject a bad signature anyway, but this
            // turns that into a readable message instead of a system error.
            val problem = verifyDownloadedApk(context, apkFile)
            if (problem != null) {
                onError(problem)
                return
            }
            // The installer must be started from the main thread; the download
            // itself is deliberately kept off it.
            Handler(Looper.getMainLooper()).post {
                try {
                    installWithPackageInstaller(context, apkFile)
                } catch (e: Exception) {
                    onError("Could not start the installer: ${e.message}")
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "Download failed.")
        }
    }

    /**
     * Hands the APK to Android's PackageInstaller instead of firing an
     * ACTION_VIEW intent at the system installer.
     *
     * With `USER_ACTION_NOT_REQUIRED` (Android 12+) a same-signer update of an
     * app that already holds "install unknown apps" is installed without any
     * further confirmation. On Android 8-11 that flag does not exist, so the
     * system may still show its own confirmation screen — the result comes back
     * to [InstallResultReceiver] either way.
     */
    fun installWithPackageInstaller(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // No "Do you want to update this app?" dialog on 12+.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val resultIntent = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                // MUTABLE: the system adds the status extras to this intent.
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * Null when [apkFile] is this app, signed with the installed app's key, and
     * a higher versionCode; otherwise a message for the user.
     */
    private fun verifyDownloadedApk(context: Context, apkFile: File): String? {
        val archive = archiveInfo(context, apkFile) ?: return "The downloaded file is not a valid APK."
        if (archive.packageName != context.packageName) {
            return "The downloaded APK belongs to a different app."
        }
        val archiveSigners = signers(archive)
        val installedSigners = signers(installedInfo(context))
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) {
            return "Could not read the APK signature."
        }
        val sameKey = archiveSigners.any { a -> installedSigners.any { b -> a.contentEquals(b) } }
        if (!sameKey) {
            return "The downloaded APK is signed with a different key — it cannot update this app."
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION") archive.versionCode.toLong()
        }
        if (archiveCode <= BuildConfig.VERSION_CODE.toLong()) {
            return "The downloaded APK is not newer than the installed version."
        }
        return null
    }

    private fun archiveInfo(context: Context, apkFile: File): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }

    private fun installedInfo(context: Context): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }

    private fun signers(info: PackageInfo?): List<ByteArray> {
        if (info == null) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() } ?: emptyList()
        }
    }

    /** Returns true if this app may install packages, or if the user just needs the prompt. */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Open the system screen where the user allows installs from this app. */
    fun openInstallPermissionScreen(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    // ------------------------------------------------------------ scheduling

    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_PENDING = "pending_update"

    /** Twelve hours: how often the background worker checks, and the throttle
     *  that stops the launch check running on every single start. */
    const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldCheckNow(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /**
     * Remembers an update found in the background, so the dashboard can offer
     * it the next time the app is open. Only one is kept — the newest.
     */
    fun savePending(context: Context, info: UpdateInfo) {
        val json = JSONObject().apply {
            put("versionName", info.versionName)
            put("versionCode", info.versionCode)
            put("tag", info.tag)
            put("apkUrl", info.apkUrl)
            put("notes", info.notes)
        }
        prefs(context).edit().putString(KEY_PENDING, json.toString()).apply()
    }

    /** Returns the remembered update once, and forgets it. */
    fun takePending(context: Context): UpdateInfo? {
        val store = prefs(context)
        val raw = store.getString(KEY_PENDING, null) ?: return null
        store.edit().remove(KEY_PENDING).apply()
        return try {
            val json = JSONObject(raw)
            UpdateInfo(
                versionName = json.optString("versionName", ""),
                versionCode = json.optInt("versionCode", -1),
                tag = json.optString("tag", ""),
                apkUrl = json.optString("apkUrl", ""),
                notes = json.optString("notes", "")
            ).takeIf { it.apkUrl.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun getUrl(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "BloodDonation-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Compare dotted version names, e.g. 1.10 > 1.2. Returns >0 if a is newer. */
    fun compareVersionNames(a: String, b: String): Int {
        val pa = a.trim().split(".", "-").mapNotNull { it.toIntOrNull() }
        val pb = b.trim().split(".", "-").mapNotNull { it.toIntOrNull() }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
