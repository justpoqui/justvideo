package com.justvideo.app.editor

import kotlin.math.abs

// =====================================================================
// Snapping.kt
// "Magnet" behaviour while dragging: if an edge comes close to the
// playhead or to another clip's edge, it jumps exactly onto it, and the
// phone gives a small vibration.
// =====================================================================

object Snapping {

    /** Result: the (maybe snapped) time, and which point it snapped to (null = no snap). */
    data class Result(val timeMs: Long, val snappedTo: Long?)

    // STEP 1: Find the closest snap point within the threshold.
    // The threshold is in PIXELS (what your finger feels), so it's
    // converted to ms with the current zoom.
    fun snap(timeMs: Long, points: List<Long>, pxPerMs: Float, thresholdPx: Float): Result {
        val thresholdMs = thresholdPx / pxPerMs
        val best = points.minByOrNull { abs(it - timeMs) } ?: return Result(timeMs, null)
        return if (abs(best - timeMs) <= thresholdMs) Result(best, best) else Result(timeMs, null)
    }
}
