package com.justvideo.app.editor

// =====================================================================
// SilenceCutter.kt
// Phase 4 "Remove silences": finds the parts of a clip worth keeping by
// cutting out dead air. It works on loudness measured every 100 ms.
// A pause only counts if it's longer than [minSilenceMs], and a little
// padding is kept around speech, so cuts never feel abrupt.
// Pure code, unit-tested.
// =====================================================================

object SilenceCutter {

    /** A part of the clip to keep, in ms from the clip's start. */
    data class Range(val startMs: Long, val endMs: Long)

    /**
     * STEP 1: [energyDb] = loudness every 100 ms. The silence threshold adapts
     * to the clip: 12 dB above its quiet floor, but never above -30 dBFS.
     */
    fun keepRanges(
        energyDb: FloatArray,
        minSilenceMs: Long = 600,
        padMs: Long = 150,
        thresholdDb: Float? = null,
    ): List<Range> {
        if (energyDb.isEmpty()) return emptyList()
        val sorted = energyDb.sortedArray()
        val floor = sorted[(sorted.size * 0.1f).toInt().coerceAtMost(sorted.lastIndex)]
        val threshold = thresholdDb ?: minOf(floor + 12f, -30f)
        val quiet = BooleanArray(energyDb.size) { energyDb[it] < threshold }

        // STEP 2: Find long quiet runs.
        val minFrames = (minSilenceMs / 100).toInt().coerceAtLeast(1)
        val silences = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < quiet.size) {
            if (quiet[i]) {
                var j = i
                while (j < quiet.size && quiet[j]) j++
                if (j - i >= minFrames) silences += i to j
                i = j
            } else i++
        }

        // STEP 3: Keep everything between silences, with padding.
        val total = energyDb.size * 100L
        val keep = mutableListOf<Range>()
        var cursor = 0L
        for ((a, b) in silences) {
            val cutStart = a * 100L + padMs
            val cutEnd = b * 100L - padMs
            if (cutEnd - cutStart < 200) continue
            if (cutStart > cursor) keep += Range(cursor, cutStart)
            cursor = cutEnd
        }
        if (total > cursor) keep += Range(cursor, total)
        return keep.filter { it.endMs - it.startMs >= 200 }
    }
}
