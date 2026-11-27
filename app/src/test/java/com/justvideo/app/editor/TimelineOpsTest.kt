package com.justvideo.app.editor

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaInfo
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// =====================================================================
// TimelineOpsTest.kt
// Checks the editing logic on the build machine (no phone needed).
// =====================================================================

class TimelineOpsTest {

    // STEP 1: Helpers that make fake imported files.
    private var n = 0
    private fun ids() = { "c${n++}" }

    private fun info(kind: MediaKind, durationMs: Long) = MediaInfo(
        uri = "content://x/${n}", kind = kind, displayName = "f", durationMs = durationMs,
        width = 1920, height = 1080, rotationDegrees = 0, hasAudio = kind != MediaKind.IMAGE,
    )

    // STEP 2: Tests.
    @Test
    fun videoClipUsesWholeFile() {
        val c = TimelineOps.clipFrom(info(MediaKind.VIDEO, 12_345), ids())
        assertEquals(0, c.trimStartMs)
        assertEquals(12_345, c.trimEndMs)
        assertEquals(12_345, c.durationMs)
    }

    @Test
    fun imageGetsDefaultDuration() {
        val c = TimelineOps.clipFrom(info(MediaKind.IMAGE, 0), ids())
        assertEquals(Clip.IMAGE_DEFAULT_DURATION_MS, c.durationMs)
        assertEquals(Clip.IMAGE_MAX_DURATION_MS, c.sourceDurationMs)
    }

    @Test
    fun appendVideoAddsToEndAndSumsDuration() {
        val a = TimelineOps.clipFrom(info(MediaKind.VIDEO, 5_000), ids())
        val b = TimelineOps.clipFrom(info(MediaKind.IMAGE, 0), ids())
        val tl = TimelineOps.appendVideo(TimelineOps.appendVideo(Timeline(), listOf(a)), listOf(b))
        assertEquals(listOf(a.id, b.id), tl.videoClips.map { it.id })
        assertEquals(8_000, tl.durationMs)
    }

    @Test
    fun audioClipsAreLaidOutBackToBack() {
        val a = TimelineOps.clipFrom(info(MediaKind.AUDIO, 4_000), ids())
        val b = TimelineOps.clipFrom(info(MediaKind.AUDIO, 6_000), ids())
        var tl = TimelineOps.appendAudio(Timeline(), listOf(a, b))
        assertEquals(listOf(0L, 4_000L), tl.audioClips.map { it.timelineOffsetMs })
        val c = TimelineOps.clipFrom(info(MediaKind.AUDIO, 1_000), ids())
        tl = TimelineOps.appendAudio(tl, listOf(c))
        assertEquals(10_000, tl.audioClips.last().timelineOffsetMs)
        assertEquals(11_000, tl.audioEndMs)
    }

    @Test
    fun deleteRemovesFromEitherTrack() {
        val v = TimelineOps.clipFrom(info(MediaKind.VIDEO, 1_000), ids())
        val a = TimelineOps.clipFrom(info(MediaKind.AUDIO, 1_000), ids())
        val tl = TimelineOps.appendAudio(TimelineOps.appendVideo(Timeline(), listOf(v)), listOf(a))
        assertTrue(TimelineOps.delete(tl, v.id).videoClips.isEmpty())
        assertTrue(TimelineOps.delete(tl, a.id).audioClips.isEmpty())
    }
}
