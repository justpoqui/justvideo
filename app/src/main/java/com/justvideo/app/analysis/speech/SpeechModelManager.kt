package com.justvideo.app.analysis.speech

import android.content.Context
import com.justvideo.app.analysis.model.SpeechModel
import com.justvideo.app.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// =====================================================================
// SpeechModelManager.kt
// The speech model (whisper "ggml" file, 31–57 MB) is downloaded ONCE
// from the whisper.cpp project's public model page, then everything runs
// offline on the phone. This class downloads, stores and finds it.
// =====================================================================

class SpeechModelManager(private val context: Context) {

    private val dir get() = File(context.filesDir, "models").apply { mkdirs() }

    /** Download progress 0..1 for the model being downloaded (null = not downloading). */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    fun file(model: SpeechModel) = File(dir, model.fileName)

    // A partly-downloaded file never counts: it's only renamed when complete.
    fun isReady(model: SpeechModel): Boolean = file(model).let { it.exists() && it.length() > 1_000_000 }

    // STEP 1: Download (resumable: no; small enough to restart). Runs on IO.
    suspend fun download(model: SpeechModel) = withContext(Dispatchers.IO) {
        val target = file(model)
        val part = File(dir, model.fileName + ".part")
        _progress.value = 0f
        try {
            var url = URL(model.url)
            var conn: HttpURLConnection
            // Follow redirects by hand (Hugging Face → its file CDN, a different host).
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "JustVideo-Android")
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
                }
                if (code != 200) throw IOException("Model download failed (HTTP $code)")
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (model.sizeMb * 1_048_576L)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            conn.disconnect()
            // STEP 2: Check it actually loads before keeping it.
            if (!WhisperBridge.available) throw IOException("Speech engine not available on this phone")
            WhisperBridge.load(part.absolutePath)?.close() ?: throw IOException("Downloaded model is damaged. Please try again.")
            target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't save the model")
            AppLog.i(TAG, "Model ${model.fileName} ready (${target.length() / 1_048_576} MB)")
        } finally {
            part.delete()
            _progress.value = null
        }
    }

    fun delete(model: SpeechModel) {
        file(model).delete()
    }

    private companion object {
        const val TAG = "SpeechModel"
    }
}
