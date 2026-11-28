package com.justvideo.app.ui.editor.timeline

// =====================================================================
// TimelineScale.kt
// The math behind the timeline: converting time ↔ pixels, zoom limits,
// and how far apart the ruler's marks are. Pure code, so it is
// unit-tested.
//
// The playhead is a fixed line in the CENTER of the timeline, and the
// clips slide underneath it (like CapCut).
// =====================================================================

object TimelineScale {

    // STEP 1: Zoom limits, in pixels per millisecond.
    // Most zoomed in: 1 second = 600 px (single frames at 30 fps ≈ 20 px).
    // Most zoomed out: a 2-hour video still fits in about 1,500 px.
    const val MAX_PX_PER_MS = 0.6f
    const val MIN_PX_PER_MS = 0.0002f

    fun clampZoom(pxPerMs: Float): Float = pxPerMs.coerceIn(MIN_PX_PER_MS, MAX_PX_PER_MS)

    // STEP 2: Where a time is drawn on screen, given the time under the
    // playhead (center) and the zoom.
    fun timeToX(timeMs: Double, playheadMs: Double, centerX: Float, pxPerMs: Float): Float =
        (centerX + (timeMs - playheadMs) * pxPerMs).toFloat()

    /** The reverse: which time is under screen position x. */
    fun xToTime(x: Float, playheadMs: Double, centerX: Float, pxPerMs: Float): Double =
        playheadMs + (x - centerX) / pxPerMs

    // STEP 3: Ruler spacing. Pick the smallest "nice" step whose labels
    // are at least [minLabelPx] apart, so they never overlap.
    private val niceStepsMs = longArrayOf(
        100, 250, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
        60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000,
    )

    fun rulerStepMs(pxPerMs: Float, minLabelPx: Float): Long =
        niceStepsMs.firstOrNull { it * pxPerMs >= minLabelPx } ?: niceStepsMs.last()

    // STEP 4: Ruler label text: "0:05", "1:30", "1:02:00", or "0.5s"
    // when zoomed in below a second.
    fun rulerLabel(timeMs: Long, stepMs: Long): String {
        if (stepMs < 1_000 && timeMs % 1_000 != 0L) return "%.1fs".format(timeMs / 1000.0)
        val s = timeMs / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    // STEP 5: Round a time to a "bucket", so nearby thumbnail requests
    // share a cached image instead of each decoding a new frame.
    fun thumbnailBucketMs(timeMs: Long, pxPerMs: Float, tilePx: Float): Long {
        val tileMs = (tilePx / pxPerMs).toLong().coerceAtLeast(100)
        // Buckets are powers of two × 100 ms, so zooming a little reuses them.
        var bucket = 100L
        while (bucket * 2 <= tileMs) bucket *= 2
        return (timeMs / bucket) * bucket
    }
}
