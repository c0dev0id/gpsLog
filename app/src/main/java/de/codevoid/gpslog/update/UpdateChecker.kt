package de.codevoid.gpslog.update

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The APK asset of the rolling `dev` pre-release. [sha] is the short commit it was built from. */
data class Nightly(val sha: String, val downloadUrl: String)

/**
 * Talks to the public GitHub releases API — no token, since release assets on a public repo are
 * downloadable anonymously. The nightly is a single rolling pre-release at tag `dev` whose asset is
 * named `gpslog-dev-<short-sha>.apk`, matching the installed `versionName` of `dev-<short-sha>`.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/c0dev0id/gpsLog/releases/tags/dev"
    private const val USER_AGENT = "gpsLog-updater"

    /** The published nightly, or null if the `dev` release has no APK asset. Throws on network/HTTP errors. */
    fun fetchLatest(): Nightly? {
        val body = httpGet(API)
        val assets = JSONObject(body).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk")) {
                val sha = name.removePrefix("gpslog-dev-").removeSuffix(".apk")
                return Nightly(sha = sha, downloadUrl = asset.optString("browser_download_url"))
            }
        }
        return null
    }

    /** Streams [url] to [dest], reporting fractional progress when the server sends a content length. */
    fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val conn = open(url).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = open(url).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("GitHub API returned HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            // GitHub rejects requests without a User-Agent with 403.
            setRequestProperty("User-Agent", USER_AGENT)
        }
}
