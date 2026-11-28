package com.justvideo.app.editor

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// =====================================================================
// TimelineEditTest.kt
// Checks trim, split, reorder, audio moves and snapping.
// =====================================================================

class TimelineEditTest {

    // STEP 1: Helpers.
    private fun clip(id: String, kind: MediaKind, ms: Long, offset: Long = 0) = Clip(
        id = id, sourceUri = "content://$id", kind = kind, displayName = id,
        sourceDurationMs = ms, trimStartMs = 0, trimEndMs = ms, timelineOffsetMs = offset,
    )

    private val tl = Timeline(
        videoClips = listOf(clip("a", MediaKind.VIDEO, 10_000), clip("b", MediaKind.VIDEO, 5_000)),
        audioClips = listOf(clip("m", MediaKind.AUDIO, 8_000, offset = 2_000)),
    )

    // STEP 2: Trim.
    @Test
    fun trimEndIsClampedToSourceAndMinimum() {
        assertEquals(6_000, TimelineOps.trimEnd(tl, "a", 6_000).videoClips[0].trimEndMs)
        assertEquals(10_000, TimelineOps.trimEnd(tl, "a", 99_999).videoClips[0].trimEndMs)
        assertEquals(TimelineOps.MIN_CLIP_MS, TimelineOps.trimEnd(tl, "a", 0).videoClips[0].trimEndMs)
    }

    @Test
    fun trimStartOfAudioKeepsItsEndInPlace() {
        val m = TimelineOps.trimStart(tl, "m", 1_000).audioClips[0]
        assertEquals(1_000, m.trimStartMs)
        assertEquals(3_000, m.timelineOffsetMs)
        assertEquals(10_000, m.timelineOffsetMs + m.durationMs) // end unchanged
    }

    @Test
    fun trimStartOfVideoChangesTotalLength() {
        val t = TimelineOps.trimStart(tl, "a", 4_000)
        assertEquals(4_000, t.videoClips[0].trimStartMs)
        assertEquals(11_000, t.durationMs)
    }

    // STEP 3: Split.
    @Test
    fun splitVideoUnderPlayhead() {
        val t = TimelineOps.splitAt(tl, 12_000, null) { "new" }
        assertEquals(listOf("a", "b", "new"), t.videoClips.map { it.id })
        assertEquals(2_000, t.videoClips[1].durationMs)
        assertEquals(3_000, t.videoClips[2].durationMs)
        assertEquals(2_000, t.videoClips[2].trimStartMs)
        assertEquals(tl.durationMs, t.durationMs) // splitting never changes length
    }

    @Test
    fun splitSelectedAudio() {
        val t = TimelineOps.splitAt(tl, 5_000, "m") { "m2" }
        assertEquals(listOf(2_000L, 5_000L), t.audioClips.map { it.timelineOffsetMs })
        assertEquals(3_000, t.audioClips[1].trimStartMs)
    }

    @Test
    fun splitTooCloseToAnEdgeDoesNothing() {
        assertSame(tl, TimelineOps.splitAt(tl, 10_050, null))
        assertFalse(TimelineOps.canSplitAt(tl, 99_000, null))
        assertTrue(TimelineOps.canSplitAt(tl, 3_000, null))
    }

    // STEP 4: Reorder + move.
    @Test
    fun moveVideoReorders() {
        assertEquals(listOf("b", "a"), TimelineOps.moveVideo(tl, "a", 1).videoClips.map { it.id })
        assertEquals(listOf("b", "a"), TimelineOps.moveVideo(tl, "b", 0).videoClips.map { it.id })
    }

    @Test
    fun moveAudioNeverGoesNegative() {
        assertEquals(0, TimelineOps.moveAudio(tl, "m", -500).audioClips[0].timelineOffsetMs)
    }

    // STEP 5: Snapping.
    @Test
    fun snapsWithinThreshold() {
        val r = Snapping.snap(10_040, listOf(0, 10_000, 15_000), pxPerMs = 0.1f, thresholdPx = 8f) // 80 ms
        assertEquals(10_000, r.timeMs)
        val far = Snapping.snap(10_200, listOf(0, 10_000), pxPerMs = 0.1f, thresholdPx = 8f)
        assertNull(far.snappedTo)
    }

    @Test
    fun edgeTimesSkipTheDraggedClip() {
        val edges = TimelineOps.edgeTimes(tl, exceptClipId = "m")
        assertTrue(15_000L in edges)
        assertFalse(2_000L in edges)
    }
}
