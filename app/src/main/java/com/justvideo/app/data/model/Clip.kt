package com.justvideo.app.data.model

import kotlinx.serialization.Serializable

// =====================================================================
// Clip.kt
// One piece of media placed on the timeline.
// =====================================================================

// STEP 1: What kind of file a clip comes from.
@Serializable
enum class MediaKind { VIDEO, IMAGE, AUDIO }

// STEP 2: The clip itself.
// A clip never changes the original file. It only stores WHICH part of the
// file to use (trimStartMs..trimEndMs). Trimming just moves those two numbers.
@Serializable
data class Clip(
    /** Unique id, so the UI can track a clip while it is dragged or reordered. */
    val id: String,
    /** content:// link to the original file in your gallery/storage. */
    val sourceUri: String,
    val kind: MediaKind,
    /** File name shown in the UI. */
    val displayName: String,
    /**
     * Full length of the source file in ms.
     * Photos have no real length, so they get [IMAGE_MAX_DURATION_MS]
     * as the most you can stretch them to.
     */
    val sourceDurationMs: Long,
    /** Where in the source file this clip starts (ms). */
    val trimStartMs: Long,
    /** Where in the source file this clip ends (ms). */
    val trimEndMs: Long,
    /** Pixel size and rotation of the source (0 for audio). */
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    /** False for photos and for silent videos. */
    val hasAudio: Boolean = false,
    /**
     * AUDIO TRACK ONLY: where the clip starts on the timeline (ms).
     * Video-track clips play back-to-back, so for them this is ignored.
     */
    val timelineOffsetMs: Long = 0,
) {
    /** How long the clip plays on the timeline. */
    val durationMs: Long get() = trimEndMs - trimStartMs

    companion object {
        /** A photo shows for 3 seconds when first added. */
        const val IMAGE_DEFAULT_DURATION_MS = 3_000L

        /** The longest you can stretch a photo. */
        const val IMAGE_MAX_DURATION_MS = 60_000L
    }
}
