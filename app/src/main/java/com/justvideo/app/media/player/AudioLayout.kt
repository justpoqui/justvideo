package com.justvideo.app.media.player

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.Timeline

// =====================================================================
// AudioLayout.kt
// Works out how the audio track plays: which part of each audio clip,
// and how much silence goes before it. Pure logic (no Android), so it is
// unit-tested.
//
// Rules:
// - Audio clips play at their timelineOffsetMs, and gaps between them
//   are silence.
// - Overlapping clips: a later clip starts where the earlier one ends
//   (Phase 1 has only one audio track).
// - Nothing plays past the end of the video, so music longer than the
//   video is cut off.
// =====================================================================

/** One piece of the audio track: [gapBeforeMs] of silence, then the clip from [startMs] to [endMs] (source time). */
data class AudioSegment(val gapBeforeMs: Long, val clip: Clip, val startMs: Long, val endMs: Long)

object AudioLayout {

    fun plan(timeline: Timeline): List<AudioSegment> {
        val videoEnd = timeline.durationMs
        var cursor = 0L // the point on the timeline we have filled up to
        val out = mutableListOf<AudioSegment>()

        // STEP 1: Go through the clips in timeline order.
        for (clip in timeline.audioClips.sortedBy { it.timelineOffsetMs }) {
            if (cursor >= videoEnd) break
            val placedAt = maxOf(clip.timelineOffsetMs, cursor)

            // STEP 2: If this clip overlaps the previous one, skip its start.
            val skipped = placedAt - clip.timelineOffsetMs
            val start = clip.trimStartMs + skipped
            if (start >= clip.trimEndMs) continue

            // STEP 3: Cut it off where the video ends.
            val room = videoEnd - placedAt
            val end = minOf(clip.trimEndMs, start + room)
            if (end <= start) continue

            out += AudioSegment(gapBeforeMs = placedAt - cursor, clip = clip, startMs = start, endMs = end)
            cursor = placedAt + (end - start)
        }
        return out
    }
}
