package com.justvideo.app.media.player

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// =====================================================================
// AudioLayoutTest.kt
// Checks how the audio track is laid out under the video.
// =====================================================================

class AudioLayoutTest {

    // STEP 1: Helpers.
    private fun clip(id: String, kind: MediaKind, durationMs: Long, offsetMs: Long = 0) = Clip(
        id = id, sourceUri = "content://$id", kind = kind, displayName = id,
        sourceDurationMs = durationMs, trimStartMs = 0, trimEndMs = durationMs, timelineOffsetMs = offsetMs,
    )

    private fun video(ms: Long) = clip("v$ms", MediaKind.VIDEO, ms)

    // STEP 2: Tests.
    @Test
    fun musicLongerThanVideoIsCut() {
        val tl = Timeline(videoClips = listOf(video(10_000)), audioClips = listOf(clip("a", MediaKind.AUDIO, 180_000)))
        val plan = AudioLayout.plan(tl)
        assertEquals(1, plan.size)
        assertEquals(0L, plan[0].gapBeforeMs)
        assertEquals(10_000L, plan[0].endMs - plan[0].startMs)
    }

    @Test
    fun gapsBecomeSilence() {
        val tl = Timeline(
            videoClips = listOf(video(30_000)),
            audioClips = listOf(clip("a", MediaKind.AUDIO, 5_000, offsetMs = 2_000), clip("b", MediaKind.AUDIO, 5_000, offsetMs = 10_000)),
        )
        val plan = AudioLayout.plan(tl)
        assertEquals(listOf(2_000L, 3_000L), plan.map { it.gapBeforeMs })
    }

    @Test
    fun overlappingClipStartsWhereThePreviousEnds() {
        val tl = Timeline(
            videoClips = listOf(video(30_000)),
            audioClips = listOf(clip("a", MediaKind.AUDIO, 8_000), clip("b", MediaKind.AUDIO, 8_000, offsetMs = 5_000)),
        )
        val plan = AudioLayout.plan(tl)
        assertEquals(0L, plan[1].gapBeforeMs)
        assertEquals(3_000L, plan[1].startMs) // its first 3 s were covered by clip a
    }

    @Test
    fun audioAfterTheVideoIsDropped() {
        val tl = Timeline(videoClips = listOf(video(5_000)), audioClips = listOf(clip("a", MediaKind.AUDIO, 5_000, offsetMs = 6_000)))
        assertTrue(AudioLayout.plan(tl).isEmpty())
    }
}
