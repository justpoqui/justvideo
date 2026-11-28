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
// and gives us undo/redo almost for free (we keep the old copies).
// =====================================================================

object TimelineOps {

    /** No clip can be trimmed shorter than this. */
    const val MIN_CLIP_MS = 100L

    // STEP 1: Turn an imported file into a clip that uses the whole file.
    fun clipFrom(info: MediaInfo, idFactory: () -> String = ::newId): Clip {
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

    // STEP 5: Trim the START of a clip (drag its left edge). For audio
    // clips the start also moves on the timeline, so the clip's end stays
    // where it is.
    fun trimStart(timeline: Timeline, clipId: String, newTrimStartMs: Long): Timeline =
        mapClip(timeline, clipId) { c ->
            var start = newTrimStartMs.coerceIn(0, c.trimEndMs - MIN_CLIP_MS)
            if (c.kind == MediaKind.AUDIO) {
                // Don't let the clip slide before 0 on the timeline.
                start = start.coerceAtLeast(c.trimStartMs - c.timelineOffsetMs)
                c.copy(trimStartMs = start, timelineOffsetMs = c.timelineOffsetMs + (start - c.trimStartMs))
            } else {
                c.copy(trimStartMs = start)
            }
        }

    // STEP 6: Trim the END of a clip (drag its right edge).
    fun trimEnd(timeline: Timeline, clipId: String, newTrimEndMs: Long): Timeline =
        mapClip(timeline, clipId) { c ->
            c.copy(trimEndMs = newTrimEndMs.coerceIn(c.trimStartMs + MIN_CLIP_MS, c.sourceDurationMs))
        }

    // STEP 7: Split. Cut a clip in two at a TIMELINE time. Prefers the
    // selected clip; otherwise it cuts the video clip under the playhead.
    // Returns the same timeline if there's nothing to cut there (or the
    // cut would leave a piece shorter than MIN_CLIP_MS).
    fun splitAt(timeline: Timeline, atMs: Long, preferredClipId: String? = null, idFactory: () -> String = ::newId): Timeline {
        // Audio clip (only when it's the selected one).
        timeline.audioClips.firstOrNull { it.id == preferredClipId }?.let { a ->
            val local = atMs - a.timelineOffsetMs
            if (local < MIN_CLIP_MS || local > a.durationMs - MIN_CLIP_MS) return timeline
            val cut = a.trimStartMs + local
            val left = a.copy(trimEndMs = cut)
            val right = a.copy(id = idFactory(), trimStartMs = cut, timelineOffsetMs = a.timelineOffsetMs + local)
            val i = timeline.audioClips.indexOf(a)
            return timeline.copy(audioClips = timeline.audioClips.toMutableList().apply { set(i, left); add(i + 1, right) })
        }
        // Video clip under the playhead.
        val (index, clipStart) = videoClipAt(timeline, atMs) ?: return timeline
        val c = timeline.videoClips[index]
        val local = atMs - clipStart
        if (local < MIN_CLIP_MS || local > c.durationMs - MIN_CLIP_MS) return timeline
        val cut = c.trimStartMs + local
        val left = c.copy(trimEndMs = cut)
        val right = c.copy(id = idFactory(), trimStartMs = cut)
        return timeline.copy(videoClips = timeline.videoClips.toMutableList().apply { set(index, left); add(index + 1, right) })
    }

    /** True if [splitAt] would actually cut something at this time. */
    fun canSplitAt(timeline: Timeline, atMs: Long, preferredClipId: String?): Boolean =
        splitAt(timeline, atMs, preferredClipId) { "probe" } != timeline

    // STEP 8: Reorder: move a video clip to a new position in the list.
    fun moveVideo(timeline: Timeline, clipId: String, toIndex: Int): Timeline {
        val from = timeline.videoClips.indexOfFirst { it.id == clipId }
        if (from < 0) return timeline
        val list = timeline.videoClips.toMutableList()
        val clip = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), clip)
        return timeline.copy(videoClips = list)
    }

    // STEP 9: Slide an audio clip to a new place on the timeline.
    fun moveAudio(timeline: Timeline, clipId: String, newOffsetMs: Long): Timeline =
        mapClip(timeline, clipId) { it.copy(timelineOffsetMs = newOffsetMs.coerceAtLeast(0)) }

    // STEP 10: Helpers.

    /** Where each video clip starts on the timeline (same order as videoClips). */
    fun videoStarts(timeline: Timeline): List<Long> {
        var t = 0L
        return timeline.videoClips.map { c -> t.also { t += c.durationMs } }
    }

    /** The video clip under timeline time [atMs], as (index, start time), or null. */
    fun videoClipAt(timeline: Timeline, atMs: Long): Pair<Int, Long>? {
        var start = 0L
        timeline.videoClips.forEachIndexed { i, c ->
            if (atMs >= start && atMs < start + c.durationMs) return i to start
            start += c.durationMs
        }
        return null
    }

    /** Every clip edge on the timeline (for snapping), optionally skipping one clip. */
    fun edgeTimes(timeline: Timeline, exceptClipId: String? = null): List<Long> {
        val out = mutableListOf(0L)
        var t = 0L
        for (c in timeline.videoClips) {
            if (c.id != exceptClipId) out += listOf(t, t + c.durationMs)
            t += c.durationMs
        }
        for (a in timeline.audioClips) if (a.id != exceptClipId) out += listOf(a.timelineOffsetMs, a.timelineOffsetMs + a.durationMs)
        return out.distinct()
    }

    fun findClip(timeline: Timeline, clipId: String?): Clip? =
        clipId?.let { id -> timeline.videoClips.firstOrNull { it.id == id } ?: timeline.audioClips.firstOrNull { it.id == id } }

    private inline fun mapClip(timeline: Timeline, clipId: String, f: (Clip) -> Clip): Timeline =
        timeline.copy(
            videoClips = timeline.videoClips.map { if (it.id == clipId) f(it) else it },
            audioClips = timeline.audioClips.map { if (it.id == clipId) f(it) else it },
        )

    private fun newId() = UUID.randomUUID().toString()
}
