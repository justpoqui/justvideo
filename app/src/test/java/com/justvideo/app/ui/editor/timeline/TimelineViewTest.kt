package com.justvideo.app.ui.editor.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.media.thumbs.ThumbnailLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// =====================================================================
// TimelineViewTest.kt
// Draws the timeline with real clips and runs swipe, pinch and tap on it,
// to make sure drawing and gestures don't crash and scrubbing reports times.
// (The fake files can't be decoded, so thumbnails stay empty, as intended.)
// =====================================================================

@RunWith(AndroidJUnit4::class)
class TimelineViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun clip(id: String, kind: MediaKind, ms: Long, offset: Long = 0) = Clip(
        id = id, sourceUri = "content://test/$id", kind = kind, displayName = "$id.mp4",
        sourceDurationMs = ms, trimStartMs = 0, trimEndMs = ms, timelineOffsetMs = offset,
    )

    private val timeline = Timeline(
        videoClips = listOf(clip("v1", MediaKind.VIDEO, 20_000), clip("p1", MediaKind.IMAGE, 3_000), clip("v2", MediaKind.VIDEO, 40_000)),
        audioClips = listOf(clip("a1", MediaKind.AUDIO, 30_000)),
    )

    @Test
    fun swipePinchAndTapWork() {
        val scrubs = mutableListOf<Long>()
        var scrubStarts = 0
        val taps = mutableListOf<String?>()
        val loader = ThumbnailLoader(ApplicationProvider.getApplicationContext())

        compose.setContent {
            TimelineView(
                timeline = timeline, playheadMs = 0, isPlaying = false, selectedClipId = "v1",
                thumbnails = loader,
                onScrub = { scrubs += it }, onScrubStart = { scrubStarts++ }, onClipTapped = { taps += it },
            )
        }
        val node = compose.onNodeWithContentDescription("Timeline")

        // STEP 1: Swipe left → time moves forward, and the start is reported.
        node.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue("scrubbed", scrubs.isNotEmpty())
        assertTrue("moved forward", scrubs.last() > 0)
        assertTrue("never past the end", scrubs.all { it in 0..timeline.durationMs })
        assertTrue(scrubStarts >= 1)

        // STEP 2: Pinch out (zoom in) must not crash.
        node.performTouchInput {
            pinch(center - Offset(10f, 0f), center - Offset(200f, 0f), center + Offset(10f, 0f), center + Offset(200f, 0f))
        }
        compose.waitForIdle()

        // STEP 3: Tap the ruler (no clip there) → deselect (null).
        node.performTouchInput { click(Offset(centerX, 5f)) }
        compose.waitForIdle()
        assertEquals(null, taps.last())
    }

    // STEP 4: Drag the left handle of the selected clip → it gets trimmed.
    @Test
    fun draggingTheLeftHandleTrims() {
        var committed: Timeline? = null
        val loader = ThumbnailLoader(ApplicationProvider.getApplicationContext())
        compose.setContent {
            TimelineView(
                timeline = timeline, playheadMs = 0, isPlaying = false, selectedClipId = "v1",
                thumbnails = loader, onScrub = {}, onScrubStart = {}, onClipTapped = {},
                onEditCommit = { committed = it },
            )
        }
        compose.onNodeWithContentDescription("Timeline").performTouchInput {
            val y = 52.dp.toPx() + 28.dp.toPx() // middle of the video track (below ruler + layers lane)
            down(Offset(centerX, y))
            repeat(10) { moveBy(Offset(10f, 0f)) }
            up()
        }
        compose.waitForIdle()
        val v1 = committed!!.videoClips.first { it.id == "v1" }
        assertTrue("trimmed ${v1.trimStartMs}", v1.trimStartMs in 1_000..2_000) // 100 px ÷ 0.07 px/ms ≈ 1.4 s
    }

    // STEP 5: Long-press a clip and drag it left → it moves to the front.
    @Test
    fun longPressDragReorders() {
        var committed: Timeline? = null
        val loader = ThumbnailLoader(ApplicationProvider.getApplicationContext())
        compose.setContent {
            TimelineView(
                timeline = timeline, playheadMs = 21_000, isPlaying = false, selectedClipId = null,
                thumbnails = loader, onScrub = {}, onScrubStart = {}, onClipTapped = {},
                onEditCommit = { committed = it },
            )
        }
        compose.onNodeWithContentDescription("Timeline").performTouchInput {
            val y = 52.dp.toPx() + 28.dp.toPx()
            down(Offset(centerX, y))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(12) { moveBy(Offset(-10.dp.toPx(), 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("p1", "v1", "v2"), committed!!.videoClips.map { it.id })
    }
}
