package com.justvideo.app.core

import android.content.Context
import android.os.StatFs

// =====================================================================
// StorageCheck.kt
// Checks free space BEFORE starting a long export or analysis, so it
// can't fail halfway through with a full disk.
// =====================================================================

object StorageCheck {

    // STEP 1: Rough size of an exported MP4: ~12 Mbit/s of video for
    // 1080p plus 128 kbit/s of audio.
    fun estimateExportBytes(durationMs: Long, videoBitrate: Int = 12_000_000): Long =
        (durationMs / 1000.0 * (videoBitrate + 128_000) / 8).toLong()

    // STEP 2: Free bytes in the app's cache folder (where the export is
    // written before it's copied to the gallery).
    fun freeBytes(context: Context): Long = StatFs(context.cacheDir.path).availableBytes

    /**
     * STEP 3: Is there room? We need the file twice (cache + gallery copy)
     * plus a 100 MB safety margin. Returns null if OK, or a message for you.
     */
    fun checkForExport(context: Context, durationMs: Long): String? {
        val need = estimateExportBytes(durationMs) * 2 + 100L * 1024 * 1024
        val free = freeBytes(context)
        return if (free >= need) null
        else "Not enough storage: need about ${need / (1024 * 1024)} MB free, you have ${free / (1024 * 1024)} MB."
    }
}
