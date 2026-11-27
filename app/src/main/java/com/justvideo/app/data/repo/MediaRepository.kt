package com.justvideo.app.data.repo

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.MediaInfo
import com.justvideo.app.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// =====================================================================
// MediaRepository.kt
// Reads facts about picked files and keeps access to them.
// We DON'T copy your videos. A 2-hour file can be many GB, so the project
// stores a link (content:// URI) to the original instead.
// =====================================================================

class MediaRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    // STEP 1: Keep access to a picked file after the app restarts.
    // Without this, Android forgets the access when the app closes and the
    // project couldn't load its clips any more.
    fun keepAccess(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some sources don't offer long-term access. The clip still works
            // for now, so we just note it in the log.
            AppLog.w(TAG, "Could not keep access to $uri: ${e.message}")
        }
    }

    // STEP 2: Give access back when no project uses the file any more.
    fun releaseAccess(uriString: String) {
        try {
            resolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // We never held long-term access to it, so there is nothing to give back.
        }
    }

    // STEP 3: Work out what a picked file is and read its details.
    // Runs on the IO thread pool, because reading a file can be slow.
    // Returns null if the file can't be read (corrupt, unsupported, deleted).
    suspend fun probe(uri: Uri, forcedKind: MediaKind? = null): MediaInfo? = withContext(Dispatchers.IO) {
        val kind = forcedKind ?: kindFromMimeType(resolver.getType(uri)) ?: return@withContext null
        val name = displayName(uri)
        try {
            when (kind) {
                MediaKind.VIDEO, MediaKind.AUDIO -> probeTimed(uri, kind, name)
                MediaKind.IMAGE -> probeImage(uri, name)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to read $name ($uri)", e)
            null
        }
    }

    // STEP 4: Videos and audio. MediaMetadataRetriever reads only the file
    // header (fast, even for huge files), not the whole file.
    private fun probeTimed(uri: Uri, kind: MediaKind, name: String): MediaInfo? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun meta(key: Int) = r.extractMetadata(key)
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration <= 0) {
                AppLog.w(TAG, "$name has no duration; skipping")
                return null
            }
            val isVideo = kind == MediaKind.VIDEO
            return MediaInfo(
                uri = uri.toString(),
                kind = kind,
                displayName = name,
                durationMs = duration,
                width = if (isVideo) meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0 else 0,
                height = if (isVideo) meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0 else 0,
                rotationDegrees = if (isVideo) meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 else 0,
                hasAudio = if (isVideo) meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" else true,
            )
        } finally {
            r.release()
        }
    }

    // STEP 5: Photos. Read only the pixel size, not the whole image.
    private fun probeImage(uri: Uri, name: String): MediaInfo? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return MediaInfo(
            uri = uri.toString(),
            kind = MediaKind.IMAGE,
            displayName = name,
            durationMs = 0,
            width = opts.outWidth,
            height = opts.outHeight,
            rotationDegrees = 0,
            hasAudio = false,
        )
    }

    private fun kindFromMimeType(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> null
    }

    // STEP 6: The file's name as the gallery shows it (e.g. "VID_2026.mp4").
    private fun displayName(uri: Uri): String =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment ?: "media"

    private companion object {
        const val TAG = "MediaRepository"
    }
}
