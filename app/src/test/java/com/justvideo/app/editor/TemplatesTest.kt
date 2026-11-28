package com.justvideo.app.editor

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.data.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// =====================================================================
// TemplatesTest.kt
// Checks the Create-tab templates build sensible timelines.
// =====================================================================

class TemplatesTest {

    private fun clip(id: String, kind: MediaKind, ms: Long) = Clip(
        id = id, sourceUri = "content://$id", kind = kind, displayName = id,
        sourceDurationMs = if (kind == MediaKind.IMAGE) Clip.IMAGE_MAX_DURATION_MS else ms, trimStartMs = 0, trimEndMs = ms,
    )

    @Test
    fun slideshowHasKenBurnsAndCrossfades() {
        val tl = Templates.slideshow(List(4) { clip("p$it", MediaKind.IMAGE, 3_000) }, perPhotoMs = 2_500)
        assertEquals(10_000, tl.durationMs)
        assertTrue(tl.videoClips.all { it.keyframes.size == 2 && it.transform.fill })
        assertEquals(TransitionType.CROSSFADE, tl.videoClips[1].transitionIn.type)
    }

    @Test
    fun beatSyncCutsLandOnBeats() {
        val beats = List(40) { it * 500L + 200 } // 120 BPM, starting at 0.2 s
        val tl = Templates.beatSync(listOf(clip("a", MediaKind.VIDEO, 30_000), clip("b", MediaKind.VIDEO, 30_000)), clip("song", MediaKind.AUDIO, 20_000), beats)
        // 500 ms beats → cut every 2 beats = 1 s pieces, alternating videos.
        assertTrue(tl.videoClips.all { it.durationMs == 1_000L })
        assertEquals("content://a", tl.videoClips[0].sourceUri)
        assertEquals("content://b", tl.videoClips[1].sourceUri)
        assertEquals(1_000L, tl.videoClips[2].trimStartMs) // next part of video a
        assertEquals(200L, tl.audioClips.single().trimStartMs) // music starts on the first beat
    }

    @Test
    fun promoAndTutorialAddAnimatedTitles() {
        val v = listOf(clip("a", MediaKind.VIDEO, 5_000), clip("b", MediaKind.VIDEO, 5_000))
        val promo = Templates.promo(v, "BIG NEWS", "Follow for more")
        assertEquals(2, promo.texts.size)
        assertTrue(promo.texts.all { it.keyframes.isNotEmpty() })
        val tut = Templates.tutorial(v)
        assertEquals(listOf("Step 1", "Step 2"), tut.texts.map { it.text })
        assertEquals(5_000, tut.texts[1].startMs)
    }

    @Test
    fun animationsStayInsideTheTextsTime() {
        for (a in TextAnimation.entries) {
            val t = Templates.animate(TextItem("t", "Hi", startMs = 0, durationMs = 2_000), a)
            assertTrue(t.keyframes.all { it.atMs in 0..2_000 })
        }
    }
}
