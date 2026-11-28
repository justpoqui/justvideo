package com.justvideo.app.editor

// =====================================================================
// BeatDetector.kt
// For "Music videos": finds the beats in a song so cuts can land on
// them. It looks for sudden rises in loudness (onsets) measured every
// 100 ms, estimates the tempo from the typical gap between strong onsets,
// and returns a regular beat grid aligned to the song.
// Pure code, unit-tested.
// =====================================================================

object BeatDetector {

    /** Beat times (ms) in the song, plus the tempo it found. */
    data class Beats(val timesMs: List<Long>, val bpm: Float)

    fun detect(energyDb: FloatArray, maxMs: Long = Long.MAX_VALUE): Beats {
        if (energyDb.size < 20) return Beats(emptyList(), 0f)

        // STEP 1: Onset strength = how much louder than the frame before.
        val onset = FloatArray(energyDb.size) { i -> if (i == 0) 0f else (energyDb[i] - energyDb[i - 1]).coerceAtLeast(0f) }
        val mean = onset.average().toFloat()
        val peaks = (1 until onset.size - 1).filter { i ->
            onset[i] > mean * 2.5f && onset[i] >= onset[i - 1] && onset[i] >= onset[i + 1]
        }
        if (peaks.size < 4) return Beats(emptyList(), 0f)

        // STEP 2: Tempo: the most common gap between onsets, folded into
        // 60–180 BPM (0.33–1 s at our 100 ms resolution, so 3–10 frames).
        val gaps = peaks.zipWithNext { a, b -> b - a }.map { g ->
            var x = g
            while (x > 10) x /= 2
            while (x < 3) x *= 2
            x
        }
        val period = gaps.groupingBy { it }.eachCount().maxBy { it.value }.key // in 100 ms frames
        val periodMs = period * 100L

        // STEP 3: Phase: the offset that lines up with the most onsets.
        val phase = (0 until period).maxBy { ph -> peaks.count { (it - ph) % period == 0 } }
        val end = minOf(energyDb.size * 100L, maxMs)
        val times = generateSequence(phase * 100L) { it + periodMs }.takeWhile { it < end }.toList()
        return Beats(times, 60_000f / periodMs)
    }
}
