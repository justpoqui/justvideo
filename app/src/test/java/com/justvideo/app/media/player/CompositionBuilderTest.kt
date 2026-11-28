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
}
