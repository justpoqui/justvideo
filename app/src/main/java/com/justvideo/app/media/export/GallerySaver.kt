package com.justvideo.app.media.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
// GallerySaver.kt
// Copies a finished MP4 into the phone's gallery (Movies/JustVideo), so it
// shows up in Photos/Gallery and every social app's picker.
// =====================================================================

object GallerySaver {

    // STEP 1: Save and return the new gallery entry's link.
    fun saveVideo(context: Context, file: File, baseName: String = "JustVideo"): Uri {
        val resolver = context.contentResolver
        val name = "${baseName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/JustVideo")
                // "Pending" hides the file from other apps until it's completely copied.
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Gallery refused the new video")

        // STEP 2: Copy the bytes. On failure, remove the half-written entry.
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IOException("Couldn't open the gallery file")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
