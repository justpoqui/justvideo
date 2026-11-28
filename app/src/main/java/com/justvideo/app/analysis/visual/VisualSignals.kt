package com.justvideo.app.analysis.visual

import kotlin.math.abs

// =====================================================================
// VisualSignals.kt
// The math behind the VISUAL signals, kept separate from the video
// decoding so it can be unit-tested:
// - motion: how much a tiny grey thumbnail changed since the last sample
// - scene cut: a big change in BOTH pixels and brightness histogram
// - faces / close-up: filled in from ML Kit results
// Samples arrive ~1–2 per second and are combined into per-second values.
// =====================================================================

class VisualSignals(private val seconds: Int) {

    val motion = FloatArray(seconds)
    val sceneCut = FloatArray(seconds)
    val faces = FloatArray(seconds)
    val closeUp = FloatArray(seconds)

    private var prevLuma: ByteArray? = null
    private var prevHist: FloatArray? = null
    private var prevSecond = -1

    // STEP 1: One sampled frame: tiny greyscale image (e.g. 64×36), plus
    // the face results for it.
    fun addFrame(timeMs: Long, luma: ByteArray, faceCount: Int, biggestFaceFraction: Float) {
        val s = (timeMs / 1000).toInt()
        if (s !in 0 until seconds) return
        val hist = histogram(luma)
        val prev = prevLuma
        if (prev != null && prev.size == luma.size) {
            val diff = meanAbsDiff(prev, luma)
            val histDist = histDistance(prevHist!!, hist)
            // Motion is spread over the seconds since the last sample.
            for (k in (prevSecond + 1).coerceAtMost(s)..s) motion[k] = maxOf(motion[k], diff)
            if (diff > 0.25f && histDist > 0.35f) sceneCut[s] = 1f
        }
        faces[s] = maxOf(faces[s], faceCount.toFloat())
        closeUp[s] = maxOf(closeUp[s], biggestFaceFraction)
        prevLuma = luma
        prevHist = hist
        prevSecond = s
    }

    // STEP 2: Average pixel change, 0 (identical) .. 1 (completely different).
    internal fun meanAbsDiff(a: ByteArray, b: ByteArray): Float {
        var sum = 0L
        for (i in a.indices) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return sum / (a.size * 255f)
    }

    // 16-bin brightness histogram, normalized to sum 1.
    internal fun histogram(luma: ByteArray): FloatArray {
        val h = FloatArray(16)
        for (v in luma) h[(v.toInt() and 0xFF) shr 4] += 1f
        for (i in h.indices) h[i] /= luma.size.coerceAtLeast(1)
        return h
    }

    // Half the L1 distance between histograms: 0 = same, 1 = no overlap.
    internal fun histDistance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / 2f
    }
}
