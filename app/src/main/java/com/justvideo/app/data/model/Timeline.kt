package com.justvideo.app.data.model

import kotlinx.serialization.Serializable

// =====================================================================
// Timeline.kt
// Everything you have edited in one project:
// the main video track, the audio (music) track, overlays on top (text,
// stickers, picture-in-picture), captions style, and the canvas shape.
// =====================================================================

/** Caption look (TikTok-style word highlight). */
@Serializable
data class CaptionStyle(
    /** ARGB colour of the word being spoken. */
    val accent: Int = 0xFFFFD60A.toInt(),
    /** -1 = bottom, 0 = middle, 1 = top. */
    val positionY: Float = -0.45f,
    val uppercase: Boolean = true,
)

@Serializable
data class Timeline(
    /** Video/photo clips. They play back-to-back, in list order. */
    val videoClips: List<Clip> = emptyList(),
    /** Audio clips. Each one sits at its own timelineOffsetMs. */
    val audioClips: List<Clip> = emptyList(),
    /** Shape of the finished video. New projects are vertical (short-form first). */
    val canvas: CanvasFormat = CanvasFormat.VERTICAL,
    /** Burn the clips' captions into the video (preview + export). */
    val showCaptions: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle(),
    val texts: List<TextItem> = emptyList(),
    val overlays: List<OverlayItem> = emptyList(),
) {
    // STEP 1: Total length = the video track's length (speed included).
    // The export stops where the video stops, and music past that is cut off.
    val durationMs: Long get() = videoClips.sumOf { it.durationMs }

    // STEP 2: The timeline position where the audio track currently ends.
    // New audio is added here, so clips line up one after another.
    val audioEndMs: Long
        get() = audioClips.maxOfOrNull { it.timelineOffsetMs + it.durationMs } ?: 0L

    val isEmpty: Boolean get() = videoClips.isEmpty() && audioClips.isEmpty()
}
