package com.justvideo.app.editor

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.Keyframe
import com.justvideo.app.data.model.KeyframeMath
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.media.captions.CaptionPager
import com.justvideo.app.media.captions.CaptionWord
import com.justvideo.app.media.player.AudioLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// =====================================================================
// Phase34LogicTest.kt
// Checks speed math, layers, captions paging, keyframes, silence
// removal and beat detection.
// =====================================================================

class Phase34LogicTest {

    private fun clip(id: String, ms: Long, speed: Float = 1f, kind: MediaKind = MediaKind.VIDEO, offset: Long = 0) = Clip(
        id = id, sourceUri = "content://$id", kind = kind, displayName = id,
        sourceDurationMs = ms, trimStartMs = 0, trimEndMs = ms, speed = speed, timelineOffsetMs = offset,
    )

    // STEP 1: Speed.
    @Test
    fun speedChangesTimelineLengthNotSource() {
        val tl = Timeline(videoClips = listOf(clip("a", 10_000, speed = 2f)))
        assertEquals(5_000, tl.durationMs)
        val split = TimelineOps.splitAt(tl, 2_000, null) { "b" }
        assertEquals(4_000, split.videoClips[0].trimEndMs) // 2 s on the timeline = 4 s of source at 2×
        assertEquals(5_000, split.durationMs)
    }

    @Test
    fun audioLayoutRespectsSpeed() {
        val tl = Timeline(videoClips = listOf(clip("v", 6_000)), audioClips = listOf(clip("m", 30_000, speed = 2f, kind = MediaKind.AUDIO)))
        val seg = AudioLayout.plan(tl).single()
        assertEquals(12_000, seg.endMs - seg.startMs) // 6 s of timeline at 2× = 12 s of song
    }

    // STEP 2: Layers.
    @Test
    fun layersRetimeAndDelete() {
        val tl = TimelineOps.addText(Timeline(), TextItem("t", "Hi", startMs = 1_000))
        val moved = TimelineOps.retimeLayer(tl, "t", -500, 100)
        assertEquals(0, moved.texts[0].startMs)
        assertEquals(300, moved.texts[0].durationMs) // minimum length
        assertTrue(TimelineOps.deleteAny(moved, "t").texts.isEmpty())
    }

    // STEP 3: Captions.
    @Test
    fun captionPagesBreakOnPausesAndSentences() {
        val w = listOf(
            CaptionWord("This", 0, 200), CaptionWord("is", 200, 300), CaptionWord("great.", 300, 600),
            CaptionWord("Next", 700, 900), CaptionWord("one", 2_000, 2_200),
        )
        val pages = CaptionPager.pages(w)
        assertEquals(listOf(3, 1, 1), pages.map { it.words.size })
        val (page, lit) = CaptionPager.at(pages, 250)!!
        assertEquals(pages[0], page)
        assertEquals(1, lit)
        assertNull(CaptionPager.at(pages, 1_600)) // long pause: nothing shown
    }

    // STEP 4: Keyframes.
    @Test
    fun keyframesInterpolateSmoothly() {
        val k = listOf(Keyframe(0, -1f, 0f), Keyframe(1_000, 1f, 0f))
        assertEquals(-1f, KeyframeMath.at(0f, 0f, 1f, k, 0).x, 0.001f)
        assertEquals(0f, KeyframeMath.at(0f, 0f, 1f, k, 500).x, 0.001f)
        assertEquals(1f, KeyframeMath.at(0f, 0f, 1f, k, 2_000).x, 0.001f)
    }

    // STEP 5: Silence removal: 2 s talk, 2 s silence, 2 s talk.
    @Test
    fun silencesAreCut() {
        val e = FloatArray(60) { if (it in 20 until 40) -70f else -20f }
        val keep = SilenceCutter.keepRanges(e)
        assertEquals(2, keep.size)
        assertEquals(0L, keep[0].startMs)
        assertEquals(2_150L, keep[0].endMs) // keeps 150 ms after the speech
        assertEquals(3_850L, keep[1].startMs)
    }

    // STEP 6: Beats: a click every 500 ms (120 BPM).
    @Test
    fun beatsAreFoundAtTheRightTempo() {
        val e = FloatArray(200) { if (it % 5 == 2) -10f else -40f }
        val b = BeatDetector.detect(e)
        assertEquals(120f, b.bpm, 0.5f)
        assertTrue(b.timesMs.take(3) == listOf(200L, 700L, 1_200L))
    }
}
