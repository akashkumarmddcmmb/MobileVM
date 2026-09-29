package com.example.vm.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val isUpdateAvailable: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val htmlUrl: String,
    val publishedAt: String
)

/**
 * AppUpdateManager: Queries the official GitHub repository releases API,
 * detects newer versions compared to BuildConfig.VERSION_NAME, and guides
 * the user to the official release download page. Never silently installs APKs.
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/aistudio/mobilevm/releases/latest"

    suspend fun checkForUpdates(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "MobileVM-Android-App")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(
                    Exception("Server returned HTTP ${connection.responseCode}: ${connection.responseMessage}")
                )
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)

            val tagName = json.optString("tag_name", "").removePrefix("v").trim()
            val releaseName = json.optString("name", "MobileVM Release")
            val releaseNotes = json.optString("body", "Bug fixes and performance improvements.")
            val htmlUrl = json.optString("html_url", "https://github.com/aistudio/mobilevm/releases")
            val publishedAt = json.optString("published_at", "")

            var downloadUrl = htmlUrl
            val assets = json.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", htmlUrl)
                        break
                    }
                }
            }

            val currentVer = BuildConfig.VERSION_NAME
            val isNewer = isVersionNewer(latest = tagName, current = currentVer)

            Result.success(
                AppUpdateInfo(
                    isUpdateAvailable = isNewer,
                    currentVersion = currentVer,
                    latestVersion = tagName,
                    releaseTitle = releaseName,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    htmlUrl = htmlUrl,
                    publishedAt = publishedAt
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Semver version comparison (e.g. 1.2.0 vs 1.1.0).
     */
    private fun isVersionNewer(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Opens the official GitHub release page in the user's browser.
     */
    fun openReleasePage(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open release URL: ${e.message}")
        }
    }
}
