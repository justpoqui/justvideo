package com.justvideo.app.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// =====================================================================
// UpdateDownloader.kt
// Downloads the update APK into the app's private cache folder and
// checks it arrived intact.
// =====================================================================

class UpdateDownloader(private val cacheDir: File) {

    private val folder get() = File(cacheDir, "updates").apply { mkdirs() }

    // STEP 1: Stream the file to disk in small chunks, so it never sits in
    // memory all at once, and report progress. Leaving the screen cancels
    // the coroutine, which stops the download cleanly.
    suspend fun download(info: UpdateInfo, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(folder, "JustVideo-build${info.versionCode}.apk")
        val partial = File(folder, target.name + ".part")
        // GitHub sends download links as redirects (to its file servers).
        // HttpURLConnection follows those automatically because both are https.
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "JustVideo-Android-Updater")
            if (conn.responseCode != 200) throw IOException("Download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSizeBytes.takeIf { it > 0 }
            val sha = MessageDigest.getInstance("SHA-256")

            conn.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        ensureActive() // stop if cancelled
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        // Report at most ~100 times, not once per chunk.
                        val pct = if (total != null) (done * 100 / total).toInt() else -1
                        if (pct != lastReported) {
                            lastReported = pct
                            onProgress(total?.let { done.toFloat() / it })
                        }
                    }
                }
            }

            // STEP 2: Integrity check. If GitHub gave us a checksum, the file
            // must match it exactly. Otherwise it's corrupt or was tampered with.
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && info.sha256 != actual) {
                partial.delete()
                throw IOException("Downloaded file is damaged (checksum mismatch). Please try again.")
            }
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Couldn't save the update file")
            target
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // STEP 3: Delete old update files (call after start-up), so they don't
    // keep taking up storage.
    fun cleanUp() {
        folder.listFiles()?.forEach { it.delete() }
    }
}
