package com.justvideo.app.data.model

import kotlinx.serialization.Serializable

// =====================================================================
// CanvasFormat.kt
// The shape of the finished video. Each project has one (shown in the
// preview), and the same sizes are the export presets.
// Clips that don't match are fitted inside with black bars, never
// stretched or cropped (crop/zoom comes in Phase 3).
// =====================================================================

@Serializable
enum class CanvasFormat(val width: Int, val height: Int, val label: String, val use: String) {
    VERTICAL(1080, 1920, "9:16", "TikTok, Reels, Shorts"),
    LANDSCAPE(1920, 1080, "16:9", "YouTube, TV"),
    SQUARE(1080, 1080, "1:1", "Instagram feed");

    val aspectRatio: Float get() = width.toFloat() / height
}
