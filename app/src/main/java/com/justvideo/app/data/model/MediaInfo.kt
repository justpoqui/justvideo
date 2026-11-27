package com.justvideo.app.data.model

// =====================================================================
// MediaInfo.kt
// Facts about a picked file, read once when you import it.
// =====================================================================

data class MediaInfo(
    val uri: String,
    val kind: MediaKind,
    val displayName: String,
    /** 0 for photos. */
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasAudio: Boolean,
)
