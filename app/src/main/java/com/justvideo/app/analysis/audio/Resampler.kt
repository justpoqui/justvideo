package com.justvideo.app.analysis.audio

// =====================================================================
// Resampler.kt
// Converts decoded audio (any rate, any channel count) into 16 kHz MONO,
// the format whisper.cpp and the audio analyzer use. Streaming: it keeps
// a little state between chunks, so audio of any length passes through
// without being stored.
//
// Method: mix channels down to mono, then average every group of input
// samples that falls into one output sample (a "box" filter). The
// averaging also removes most frequencies that 16 kHz can't represent.
// Pure code, unit-tested.
// =====================================================================

class Resampler(inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {

    private val step = inRate.toDouble() / outRate // input samples per output sample
    private var pos = 0.0     // where the next output sample ends, in input samples
    private var acc = 0.0     // running sum for the output sample being built
    private var accCount = 0
    private var index = 0L    // input frames seen so far

    // STEP 1: Feed interleaved samples (values -1..1). Returns the new
    // 16 kHz mono samples (possibly empty).
    fun process(interleaved: FloatArray, count: Int = interleaved.size): FloatArray {
        val frames = count / channels
        val out = FloatArray((frames / step).toInt() + 2)
        var n = 0
        if (pos == 0.0) pos = step
        for (f in 0 until frames) {
            // Downmix: average the channels.
            var m = 0f
            val base = f * channels
            for (c in 0 until channels) m += interleaved[base + c]
            acc += m / channels
            accCount++
            index++
            // STEP 2: Enough input for one output sample? Emit the average.
            if (index >= pos) {
                out[n++] = (acc / accCount).toFloat()
                acc = 0.0; accCount = 0
                pos += step
            }
        }
        return if (n == out.size) out else out.copyOf(n)
    }
}
