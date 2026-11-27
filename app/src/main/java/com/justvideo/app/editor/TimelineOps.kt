package com.justvideo.app.editor

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaInfo
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import java.util.UUID

// =====================================================================
// TimelineOps.kt
// Pure editing logic: every function takes a Timeline and returns a NEW
// Timeline. No UI, no Android code. That makes these easy to unit-test,
// and later gives us undo/redo for free (we keep the old copies).
// Step 6 adds trim, split and reorder here.
// =====================================================================

object TimelineOps {

    // STEP 1: Turn an imported file into a clip that uses the whole file.
    fun clipFrom(info: MediaInfo, idFactory: () -> String = { UUID.randomUUID().toString() }): Clip {
        val isImage = info.kind == MediaKind.IMAGE
        return Clip(
            id = idFactory(),
            sourceUri = info.uri,
            kind = info.kind,
            displayName = info.displayName,
            sourceDurationMs = if (isImage) Clip.IMAGE_MAX_DURATION_MS else info.durationMs,
            trimStartMs = 0,
            trimEndMs = if (isImage) Clip.IMAGE_DEFAULT_DURATION_MS else info.durationMs,
            width = info.width,
            height = info.height,
            rotationDegrees = info.rotationDegrees,
            hasAudio = info.hasAudio,
        )
    }

    // STEP 2: Add video/photo clips to the end of the main track.
    fun appendVideo(timeline: Timeline, clips: List<Clip>): Timeline =
        timeline.copy(videoClips = timeline.videoClips + clips)

    // STEP 3: Add audio clips one after another, starting where the
    // audio track currently ends.
    fun appendAudio(timeline: Timeline, clips: List<Clip>): Timeline {
        var cursor = timeline.audioEndMs
        val placed = clips.map { clip ->
            clip.copy(timelineOffsetMs = cursor).also { cursor += clip.durationMs }
        }
        return timeline.copy(audioClips = timeline.audioClips + placed)
    }

    // STEP 4: Remove a clip from whichever track holds it.
    fun delete(timeline: Timeline, clipId: String): Timeline =
        timeline.copy(
            videoClips = timeline.videoClips.filterNot { it.id == clipId },
            audioClips = timeline.audioClips.filterNot { it.id == clipId },
        )
}
