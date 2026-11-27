package com.justvideo.app.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// =====================================================================
// GitHubReleaseClient.kt
// Asks GitHub for the newest release of the app's repository.
// It uses Android's built-in HttpURLConnection (no extra library). The
// repository is public, so no login or token is needed.
// =====================================================================

class GitHubReleaseClient(private val repo: String) {

    // STEP 1: GET /repos/{owner}/{name}/releases/latest
    // GitHub's "latest" skips drafts and pre-releases on its own.
    // Returns null when the repo has no releases yet.
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub requires a User-Agent. A generic app name, nothing personal.
            conn.setRequestProperty("User-Agent", "JustVideo-Android-Updater")
            when (val code = conn.responseCode) {
                200 -> ReleaseParser.parse(conn.inputStream.bufferedReader().use { it.readText() })
                404 -> null // no releases published yet
                403, 429 -> throw IOException("GitHub is rate-limiting update checks. Try again later.")
                else -> throw IOException("GitHub answered HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
