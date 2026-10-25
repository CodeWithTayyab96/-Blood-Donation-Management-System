package com.tayyab.blooddonation.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
            installApk(context, apkFile)
        } catch (e: Exception) {
            onError(e.message ?: "Download failed.")
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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

    private fun getUrl(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "BloodDonation-Updater")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("GitHub returned HTTP ${connection.responseCode}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
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
