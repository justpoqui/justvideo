package com.justvideo.app.analysis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.justvideo.app.analysis.model.Highlight
import com.justvideo.app.analysis.model.HighlightSettings
import com.justvideo.app.data.db.HighlightSessionDao
import com.justvideo.app.data.db.HighlightSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

// =====================================================================
// AnalysisStore.kt
// The Auto Highlights cache + settings:
// - measurements (features + transcript) → a file per video
// - clips (with Keep/Discard) → the database (HighlightSessionEntity)
// - your Highlight settings → a small preferences file
// Re-opening an analyzed video skips the slow analysis completely.
// =====================================================================

class AnalysisStore(private val context: Context, private val dao: HighlightSessionDao) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    private val dir get() = File(context.filesDir, "analysis").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("highlights", Context.MODE_PRIVATE)

    val sessions = dao.observeAll()

    // STEP 1: A stable key for a video: its link + file size. (If the file
    // is edited/replaced, the size changes and it's analyzed again.)
    suspend fun keyFor(uri: Uri): String = withContext(Dispatchers.IO) {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L
        val md = MessageDigest.getInstance("SHA-1").digest("$uri|$size".toByteArray())
        md.joinToString("") { "%02x".format(it) }.take(20)
    }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Video"

    // STEP 2: Measurements.
    suspend fun loadResult(key: String): AnalysisResult? = withContext(Dispatchers.IO) {
        val f = File(dir, "$key.json")
        if (!f.exists()) null else runCatching { json.decodeFromString(AnalysisResult.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun saveResult(key: String, r: AnalysisResult) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(json.encodeToString(AnalysisResult.serializer(), r))
        tmp.renameTo(File(dir, "$key.json"))
    }

    // STEP 3: Clips.
    suspend fun loadHighlights(key: String): List<Highlight>? =
        dao.get(key)?.let { runCatching { json.decodeFromString(ListSerializer(Highlight.serializer()), it.highlightsJson) }.getOrNull() }

    suspend fun saveHighlights(key: String, uri: Uri, name: String, durationMs: Long, clips: List<Highlight>) {
        val existing = dao.get(key)
        dao.upsert(
            HighlightSessionEntity(
                key = key, uri = uri.toString(), name = name, durationMs = durationMs,
                createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                highlightsJson = json.encodeToString(ListSerializer(Highlight.serializer()), clips),
            )
        )
    }

    suspend fun delete(key: String) {
        dao.delete(key)
        withContext(Dispatchers.IO) { File(dir, "$key.json").delete() }
    }

    // STEP 4: Settings (weights, lengths, keywords, models).
    fun loadSettings(): HighlightSettings =
        prefs.getString("settings", null)?.let { runCatching { json.decodeFromString(HighlightSettings.serializer(), it) }.getOrNull() }
            ?: HighlightSettings()

    fun saveSettings(s: HighlightSettings) {
        prefs.edit().putString("settings", json.encodeToString(HighlightSettings.serializer(), s)).apply()
    }
}
