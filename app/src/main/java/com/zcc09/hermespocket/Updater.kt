package com.zcc09.hermespocket

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Background self-update check against the GitHub releases API.
 *
 * Fully defensive by design: every step is wrapped in try/catch and any
 * failure is logged (never thrown) so the app cannot crash because of the
 * updater. Network work runs on a daemon executor; UI hops use the main
 * looper handler.
 */
class Updater(private val activity: Activity) {

    companion object {
        private const val TAG = "HermesPocket"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Zcc09/hermes-pocket/releases/latest"
        private const val CHECK_INTERVAL_MS = 10L * 60L * 1000L
        private const val MAX_REDIRECTS = 6
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "HermesPocket-Update").apply { isDaemon = true }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * User-initiated check: ignores the 10-minute throttle that keeps automatic
     * checks quiet, because a tap on "Check for updates" is an explicit ask.
     */
    fun checkNow() {
        runCatching { store.setLastUpdateCheck(0L) }
        start()
    }

    /**
     * Starts the check if the last check was more than 10 minutes ago.
     * Safe to call from the main thread; it returns immediately.
     */
    fun start() {
        try {
            val now = System.currentTimeMillis()
            val lastCheck = store.lastUpdateCheck()
            if (now - lastCheck < CHECK_INTERVAL_MS) {
                Log.i(TAG, "Update check skipped, last check ${now - lastCheck}ms ago")
                return
            }
            executor.execute {
                try {
                    checkLatestRelease()
                } catch (t: Throwable) {
                    Log.w(TAG, "Update check failed", t)
                } finally {
                    store.setLastUpdateCheck(System.currentTimeMillis())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Update start failed", t)
        }
    }

    private val store = ServerStore(activity)

    private fun checkLatestRelease() {
        val body = fetchLatestReleaseJson() ?: return
        val json = JSONObject(body)
        val tagName = json.optString("tag_name").trim()
        val apkUrl = firstApkAsset(json)
        if (tagName.isEmpty() || apkUrl == null) {
            Log.w(TAG, "UPDATE no release or apk asset found")
            return
        }
        val candidate = tagName.removePrefix("v").removePrefix("V").trim()
        if (!VersionTools.isNewer(BuildConfig.VERSION_NAME, candidate)) {
            Log.i(TAG, "Update check: $candidate is not newer than " + BuildConfig.VERSION_NAME)
            return
        }
        Log.i(TAG, "UPDATE found=$tagName")
        showUpdateDialog(tagName, apkUrl)
    }

    private fun fetchLatestReleaseJson(): String? {
        val connection = openConnection(URL(LATEST_RELEASE_URL))
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Update check: HTTP " + connection.responseCode)
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Update check request failed", t)
            null
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun firstApkAsset(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name").trim()
            val url = asset.optString("browser_download_url").trim()
            if (!name.endsWith(".apk", ignoreCase = true) || !url.startsWith("https://")) {
                continue
            }
            if (fallback == null) fallback = url
            // CI publishes both debug and release APKs; prefer the release.
            if (name.contains("release", ignoreCase = true)) return url
        }
        return fallback
    }

    private fun showUpdateDialog(tagName: String, apkUrl: String) {
        mainHandler.post {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@post
                AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                    .setTitle("Update available")
                    .setMessage("Version $tagName is available. Install it now?")
                    .setPositiveButton("Install") { _, _ -> installApk(apkUrl) }
                    .setNegativeButton("Later", null)
                    .show()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to show update dialog", t)
            }
        }
    }

    private fun installApk(apkUrl: String) {
        executor.execute {
            try {
                val dir = File(activity.cacheDir, "updates")
                if (!dir.mkdirs() && !dir.isDirectory) {
                    Log.w(TAG, "Update directory not available")
                    return@execute
                }
                val target = File(dir, fileNameFor(apkUrl))
                downloadTo(apkUrl, target)
                if (!target.exists() || target.length() == 0L) {
                    Log.w(TAG, "Download produced an empty file")
                    return@execute
                }
                val uri = FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    target
                )
                Log.i(TAG, "UPDATE_INSTALL_URI uri=$uri")
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                mainHandler.post {
                    try {
                        activity.startActivity(intent)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Install intent failed", t)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Update install failed", t)
            }
        }
    }

    private fun fileNameFor(url: String): String {
        val segment = url.substringAfterLast('/', "").trim()
        return if (segment.isNotEmpty() && segment.contains('.')) segment
        else "hermes-pocket-update.apk"
    }

    /**
     * Downloads [urlString] into [destination], following redirects manually
     * (up to [MAX_REDIRECTS]) so the APK download works against GitHub's
     * object-storage redirects.
     */
    private fun downloadTo(urlString: String, destination: File) {
        var url = URL(urlString)
        var connection = openConnection(url)
        try {
            for (hop in 0..MAX_REDIRECTS) {
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        if (location == null || hop == MAX_REDIRECTS) {
                            Log.w(TAG, "Download redirect problem at hop $hop")
                            return
                        }
                        try {
                            connection.disconnect()
                        } catch (_: Throwable) {
                        }
                        url = URL(url, location)
                        connection = openConnection(url)
                    }
                    code in 200..299 -> {
                        connection.inputStream.use { input ->
                            destination.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        return
                    }
                    else -> {
                        Log.w(TAG, "Download: HTTP $code")
                        return
                    }
                }
            }
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "hermes-pocket")
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        return connection
    }
}
