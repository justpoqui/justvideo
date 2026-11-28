package com.justvideo.app.data.model

import kotlinx.serialization.Serializable

// =====================================================================
// Timeline.kt
// Everything you have edited in one project.
// Phase 1: one main video track + one audio track, and the canvas shape.
// =====================================================================

@Serializable
data class Timeline(
    /** Video/photo clips. They play back-to-back, in list order. */
    val videoClips: List<Clip> = emptyList(),
    /** Audio clips. Each one sits at its own timelineOffsetMs. */
    val audioClips: List<Clip> = emptyList(),
    /** Shape of the finished video. New projects are vertical (short-form first). */
    val canvas: CanvasFormat = CanvasFormat.VERTICAL,
) {
    // STEP 1: Total length = the video track's length.
    // The export stops where the video stops, and music past that is cut off.
    val durationMs: Long get() = videoClips.sumOf { it.durationMs }

    // STEP 2: The timeline position where the audio track currently ends.
    // New audio is added here, so clips line up one after another.
    val audioEndMs: Long
        get() = audioClips.maxOfOrNull { it.timelineOffsetMs + it.durationMs } ?: 0L

    val isEmpty: Boolean get() = videoClips.isEmpty() && audioClips.isEmpty()
}
