package com.justvideo.app.media.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justvideo.app.core.StorageCheck
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// =====================================================================
// CompositionBuilderTest.kt
// Checks the Media3 Composition that both the preview and the export use.
// =====================================================================

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CompositionBuilderTest {

    private fun clip(id: String, kind: MediaKind, ms: Long, offset: Long = 0) = Clip(
        id = id, sourceUri = "content://test/$id", kind = kind, displayName = id,
        sourceDurationMs = ms, trimStartMs = 0, trimEndMs = ms, timelineOffsetMs = offset,
    )

    @Test
    fun emptyTimelineHasNothingToRender() {
        assertNull(CompositionBuilder.build(Timeline()))
    }

    @Test
    fun videoAndMusicBecomeTwoSequences() {
        val tl = Timeline(
            videoClips = listOf(clip("v", MediaKind.VIDEO, 10_000), clip("p", MediaKind.IMAGE, 3_000)),
            audioClips = listOf(clip("a", MediaKind.AUDIO, 60_000, offset = 1_000)),
        )
        val c = CompositionBuilder.build(tl, CanvasFormat.VERTICAL)!!
        assertEquals(2, c.sequences.size)
        assertEquals(2, c.sequences[0].editedMediaItems.size)
        // Audio sequence: a 1 s gap, then the song (cut to the video's 13 s).
        assertEquals(2, c.sequences[1].editedMediaItems.size)
    }

    @Test
    fun exportFitsEveryClipIntoTheChosenSize() {
        val tl = Timeline(videoClips = listOf(clip("v", MediaKind.VIDEO, 5_000)))
        val c = CompositionBuilder.build(tl, CanvasFormat.SQUARE)!!
        val effects = c.sequences[0].editedMediaItems[0].effects.videoEffects
        assertTrue(effects.any { it is Presentation })
    }

    @Test
    fun storageEstimateIsSensible() {
        // 1 minute at ~12 Mbit/s ≈ 90 MB.
        val mb = StorageCheck.estimateExportBytes(60_000) / (1024 * 1024)
        assertTrue("$mb MB", mb in 80..100)
    }

    // A project using every Phase 3/4 feature still builds, with the extra
    // layers (PiP video + crossfade tails) placed before the main track.
    @Test
    fun everythingTogetherBuilds() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = clip("a", MediaKind.VIDEO, 20_000).copy(
            speed = 2f, volume = 0.5f, fadeInMs = 500, width = 1920, height = 1080,
            transform = com.justvideo.app.data.model.ClipTransform(scale = 1.5f, fill = true),
            color = com.justvideo.app.data.model.ColorAdjust(brightness = 0.1f, saturation = 1.3f, filter = com.justvideo.app.data.model.FilterType.WARM),
            captions = listOf(com.justvideo.app.media.captions.CaptionWord("Hello", 1_000, 1_400)),
            keyframes = listOf(com.justvideo.app.data.model.Keyframe(0, 0f, 0f, 1f), com.justvideo.app.data.model.Keyframe(2_000, 0.2f, 0f, 1.2f)),
        )
        val b = clip("b", MediaKind.VIDEO, 10_000).copy(transitionIn = com.justvideo.app.data.model.Transition(com.justvideo.app.data.model.TransitionType.CROSSFADE, 800))
        val p = clip("p", MediaKind.IMAGE, 3_000).copy(transitionIn = com.justvideo.app.data.model.Transition(com.justvideo.app.data.model.TransitionType.SLIDE, 500))
        val tl = Timeline(
            videoClips = listOf(a, b, p),
            texts = listOf(com.justvideo.app.data.model.TextItem("t", "Title", startMs = 0, durationMs = 4_000)),
            overlays = listOf(
                com.justvideo.app.data.model.OverlayItem("s", com.justvideo.app.data.model.OverlayKind.STICKER, "🔥", startMs = 1_000),
                com.justvideo.app.data.model.OverlayItem("v", com.justvideo.app.data.model.OverlayKind.VIDEO, "content://test/pip", startMs = 2_000, durationMs = 3_000, sourceDurationMs = 10_000, width = 1080, height = 1920),
            ),
        )
        val c = CompositionBuilder.build(tl, CanvasFormat.VERTICAL, ctx)!!
        // PiP layer, crossfade-tail layer, then the main track.
        assertEquals(3, c.sequences.size)
        assertEquals(3, c.sequences[2].editedMediaItems.size)
        // Preview (no layers burned in) still works.
        assertTrue(CompositionBuilder.build(tl, null, ctx, includeLayers = false) != null)
    }
}
