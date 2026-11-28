package com.justvideo.app.ui.editor.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// =====================================================================
// TimelineScaleTest.kt
// Checks the timeline's time ↔ pixel math and ruler spacing.
// =====================================================================

class TimelineScaleTest {

    @Test
    fun playheadTimeIsDrawnAtCenter() {
        assertEquals(500f, TimelineScale.timeToX(10_000.0, 10_000.0, 500f, 0.1f), 0.001f)
        assertEquals(600f, TimelineScale.timeToX(11_000.0, 10_000.0, 500f, 0.1f), 0.001f)
    }

    @Test
    fun xToTimeIsTheInverse() {
        val t = TimelineScale.xToTime(650f, 4_000.0, 500f, 0.05f)
        assertEquals(650f, TimelineScale.timeToX(t, 4_000.0, 500f, 0.05f), 0.001f)
    }

    @Test
    fun zoomIsClamped() {
        assertEquals(TimelineScale.MAX_PX_PER_MS, TimelineScale.clampZoom(99f), 0f)
        assertEquals(TimelineScale.MIN_PX_PER_MS, TimelineScale.clampZoom(0f), 0f)
    }

    @Test
    fun rulerLabelsNeverOverlap() {
        for (zoom in listOf(0.0002f, 0.001f, 0.01f, 0.1f, 0.6f)) {
            val step = TimelineScale.rulerStepMs(zoom, 80f)
            assertTrue("zoom $zoom", step * zoom >= 80f || step == 3_600_000L)
        }
    }

    @Test
    fun rulerLabelFormats() {
        assertEquals("0:05", TimelineScale.rulerLabel(5_000, 1_000))
        assertEquals("1:02:00", TimelineScale.rulerLabel(3_720_000, 60_000))
        assertEquals("2.5s", TimelineScale.rulerLabel(2_500, 250))
    }

    @Test
    fun nearbyThumbnailsShareABucket() {
        val a = TimelineScale.thumbnailBucketMs(10_100, 0.05f, 100f)
        val b = TimelineScale.thumbnailBucketMs(10_900, 0.05f, 100f)
        assertEquals(a, b)
    }
}
