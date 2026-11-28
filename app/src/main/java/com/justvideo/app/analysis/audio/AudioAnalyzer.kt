package com.justvideo.app.analysis.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

// =====================================================================
// AudioAnalyzer.kt
// The AUDIO signals. It's fed 16 kHz mono audio in chunks (streaming:
// the whole soundtrack is never in memory) and measures, every 100 ms:
// - loudness (RMS level in dB)
// - bass energy (for music drops)
// - "noisiness" (high-frequency share: cheering/applause/laughter are
//   noisy and broadband, while speech and music are more tonal)
// At the end it turns those into one value per second for each signal.
// Pure Kotlin (no Android), so it is unit-tested with synthetic audio.
// =====================================================================

class AudioAnalyzer(private val sampleRate: Int = 16_000) {

    private val frame = sampleRate / 10 // 100 ms
    private val rmsDb = FloatList()
    private val bassDb = FloatList()
    private val noisiness = FloatList()

    // Running state for the current 100 ms frame.
    private var n = 0
    private var sumSq = 0.0
    private var bassSq = 0.0
    private var diffSq = 0.0
    private var lowpass = 0.0
    private var prev = 0f

    // One-pole low-pass at ~150 Hz isolates the bass (kick drums, drops).
    private val alpha = 1.0 - kotlin.math.exp(-2.0 * Math.PI * 150.0 / sampleRate)

    // STEP 1: Feed audio (values -1..1).
    fun consume(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            val x = samples[i]
            sumSq += x * x
            lowpass += alpha * (x - lowpass)
            bassSq += lowpass * lowpass
            val d = x - prev
            diffSq += d * d
            prev = x
            if (++n == frame) closeFrame()
        }
    }

    private fun closeFrame() {
        val rms = sqrt(sumSq / n)
        rmsDb.add(toDb(rms))
        bassDb.add(toDb(sqrt(bassSq / n)))
        // Share of energy in fast changes (high frequencies), 0..~2.
        noisiness.add(if (sumSq > 1e-9) (diffSq / sumSq).toFloat() else 0f)
        n = 0; sumSq = 0.0; bassSq = 0.0; diffSq = 0.0
    }

    /** The finished per-second signals. */
    class Result(
        val loudness: FloatArray,
        val loudJump: FloatArray,
        val burst: FloatArray,
        val musicDrop: FloatArray,
        val energy100ms: FloatArray,
    )

    // STEP 2: Turn the 100 ms measurements into per-second signals.
    fun finish(totalSeconds: Int): Result {
        if (n > frame / 4) closeFrame()
        val e = rmsDb.toArray()
        val bass = bassDb.toArray()
        val noise = noisiness.toArray()
        val secs = totalSeconds.coerceAtLeast(0)

        val loud = FloatArray(secs) { s -> maxOf10(e, s) }
        val bassSec = FloatArray(secs) { s -> maxOf10(bass, s) }
        val noiseSec = FloatArray(secs) { s -> avgOf10(noise, s) }

        // Sudden volume jump: this second vs. the average of the 5 before.
        val jump = FloatArray(secs) { s ->
            if (s == 0) 0f else (loud[s] - mean(loud, s - 5, s)).coerceAtLeast(0f)
        }
        // Burst: loud AND noisy (relative to the video's typical loudness).
        val typical = median(loud)
        val burst = FloatArray(secs) { s ->
            val louder = ((loud[s] - typical) / 10f).coerceIn(0f, 1.5f)
            louder * (noiseSec[s] / 0.6f).coerceIn(0f, 1.5f)
        }
        // Music drop: bass jumps up vs. the previous 4 seconds.
        val drop = FloatArray(secs) { s ->
            if (s < 2) 0f else ((bassSec[s] - mean(bassSec, s - 4, s)) / 10f).coerceIn(0f, 2f)
        }
        return Result(loud, jump, burst, drop, e)
    }

    // ---- helpers ----
    private fun toDb(rms: Double): Float = (20 * log10(max(rms, 1e-5))).toFloat() // silence ≈ -100 dB

    private fun maxOf10(a: FloatArray, s: Int): Float {
        var m = -100f
        for (i in s * 10 until minOf(s * 10 + 10, a.size)) if (a[i] > m) m = a[i]
        return m
    }

    private fun avgOf10(a: FloatArray, s: Int): Float {
        var sum = 0f; var c = 0
        for (i in s * 10 until minOf(s * 10 + 10, a.size)) { sum += a[i]; c++ }
        return if (c == 0) 0f else sum / c
    }

    private fun mean(a: FloatArray, from: Int, to: Int): Float {
        val f = from.coerceAtLeast(0); if (to <= f) return a.getOrElse(f) { 0f }
        var s = 0f; for (i in f until to) s += a[i]; return s / (to - f)
    }

    private fun median(a: FloatArray): Float = if (a.isEmpty()) 0f else a.sorted()[a.size / 2]
}

/** A growable float list without boxing (2 hours of 100 ms frames = 72,000 values). */
class FloatList(initial: Int = 1024) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)

    fun clear() {
        size = 0
    }
}
