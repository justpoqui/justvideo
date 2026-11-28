package com.justvideo.app.analysis.scoring

import com.justvideo.app.analysis.model.Features
import com.justvideo.app.analysis.model.HighlightSettings
import com.justvideo.app.analysis.model.Signal
import kotlin.math.abs

// =====================================================================
// HighlightScorer.kt
// Combines every signal into ONE score per second (0..1).
//
// 1. Each signal is put on the same scale. "Continuous" signals
//    (loudness, motion, speech rate…) become "how unusual is this second
//    for THIS video" (a robust z-score: distance from the median, in MADs),
//    so a quiet podcast and a loud stream are both judged fairly.
//    "Event" signals (hype word, question, scene cut…) are already 0..1.
// 2. Weighted average using your Settings weights.
// 3. Light smoothing (3 s), so a single spike doesn't beat a great moment.
// It also keeps each signal's contribution, for the "why picked" text.
// =====================================================================

class ScoreResult(
    /** Final smoothed score per second, 0..1. */
    val score: FloatArray,
    /** contribution[signal][second], already weighted (0..weight). */
    val contribution: Map<Signal, FloatArray>,
)

object HighlightScorer {

    private val continuous = setOf(
        Signal.LOUDNESS, Signal.LOUD_JUMP, Signal.BURST, Signal.MUSIC_DROP,
        Signal.SPEECH_RATE, Signal.MOTION, Signal.FACES, Signal.CLOSE_UP,
    )

    // STEP 1: Raw array for each signal.
    fun raw(f: Features, s: Signal): FloatArray = when (s) {
        Signal.LOUDNESS -> f.loudness
        Signal.LOUD_JUMP -> f.loudJump
        Signal.BURST -> f.burst
        Signal.MUSIC_DROP -> f.musicDrop
        Signal.SPEECH_RATE -> f.speechRate
        Signal.EXCITEMENT -> f.excitement
        Signal.QUESTION -> f.question
        Signal.HYPE -> f.hypeWords
        Signal.LAUGHTER -> f.laughter
        Signal.MOTION -> f.motion
        Signal.SCENE_CUT -> f.sceneCut
        Signal.FACES -> f.faces
        Signal.CLOSE_UP -> f.closeUp
    }

    fun score(f: Features, settings: HighlightSettings): ScoreResult {
        val n = f.seconds
        val total = FloatArray(n)
        val contribution = HashMap<Signal, FloatArray>()
        var weightSum = 0f

        for (signal in Signal.entries) {
            val w = settings.weight(signal)
            val raw = raw(f, signal)
            if (w <= 0f || raw.size != n) continue
            // STEP 2: Put the signal on a 0..1 scale.
            val norm = if (signal in continuous) robustPositive(raw) else FloatArray(n) { raw[it].coerceIn(0f, 1f) }
            val c = FloatArray(n) { norm[it] * w }
            contribution[signal] = c
            for (i in 0 until n) total[i] += c[i]
            weightSum += w
        }
        if (weightSum > 0) for (i in 0 until n) total[i] /= weightSum

        // STEP 3: Smooth over 3 seconds, then stretch so the best second = 1.
        val smooth = FloatArray(n) { i ->
            var s = 0f; var c = 0
            for (k in i - 1..i + 1) if (k in 0 until n) { s += total[k] * if (k == i) 2f else 1f; c += if (k == i) 2 else 1 }
            if (c == 0) 0f else s / c
        }
        val max = smooth.maxOrNull() ?: 0f
        if (max > 0f) for (i in 0 until n) smooth[i] /= max
        return ScoreResult(smooth, contribution)
    }

    // STEP 4: "How far above normal", 0..1. Uses median and MAD (median
    // absolute deviation), which aren't thrown off by a few extreme seconds.
    // 3+ MADs above the median counts as fully unusual (1.0).
    internal fun robustPositive(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        val sorted = x.sortedArray()
        val median = sorted[sorted.size / 2]
        val dev = FloatArray(x.size) { abs(x[it] - median) }.apply { sort() }
        val mad = dev[dev.size / 2].coerceAtLeast(1e-3f) * 1.4826f
        return FloatArray(x.size) { ((x[it] - median) / mad / 3f).coerceIn(0f, 1f) }
    }
}
