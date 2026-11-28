package com.justvideo.app.analysis.speech

import com.justvideo.app.analysis.audio.FloatList
import com.justvideo.app.analysis.model.Segment
import com.justvideo.app.analysis.model.Transcript
import com.justvideo.app.analysis.model.Word
import com.justvideo.app.core.AppLog
import kotlin.math.sqrt

// =====================================================================
// SpeechAnalyzer.kt
// The SPEECH signals' source: a streaming transcriber.
// It collects 16 kHz audio and, every ~60 s, cuts at the QUIETEST moment
// of the last few seconds (so no word is split between chunks), runs
// whisper.cpp on that chunk, and keeps the words with their real times.
// =====================================================================

class SpeechAnalyzer(
    private val whisper: WhisperBridge,
    private val language: String,
    private val threads: Int,
    private val sampleRate: Int = 16_000,
    /** Where the audio being fed starts in the file (when only a range is transcribed). */
    private val startOffsetMs: Long = 0,
) {
    private val chunkTarget = 60 * sampleRate      // ~60 s per whisper call
    private val searchWindow = 5 * sampleRate      // look for a quiet cut in the last 5 s
    private val buffer = FloatList(chunkTarget + searchWindow + sampleRate)
    private var bufferStartSample = 0L             // absolute position of buffer[0]
    private val segments = mutableListOf<Segment>()
    private val words = mutableListOf<Word>()

    // STEP 1: Feed audio. Runs whisper whenever a chunk is full (blocking:
    // call from a background thread).
    fun consume(samples: FloatArray) {
        for (v in samples) buffer.add(v)
        if (buffer.size >= chunkTarget + searchWindow) {
            val data = buffer.toArray()
            val cut = quietestCut(data, chunkTarget, chunkTarget + searchWindow)
            transcribe(data.copyOf(cut))
            // Keep the remainder for the next chunk.
            val rest = data.copyOfRange(cut, data.size)
            buffer.clear()
            for (v in rest) buffer.add(v)
            bufferStartSample += cut
        }
    }

    /** STEP 2: Transcribe whatever is left, and return the full transcript. */
    fun finish(): Transcript {
        if (buffer.size > sampleRate / 2) transcribe(buffer.toArray())
        buffer.clear()
        return Transcript(segments.toList(), words.toList())
    }

    fun abort() = whisper.abort()

    private fun transcribe(pcm: FloatArray) {
        val offsetMs = startOffsetMs + bufferStartSample * 1000 / sampleRate
        val t = System.currentTimeMillis()
        val raw = whisper.transcribe(pcm, language, threads)
        val r = TranscriptParser.parse(raw, offsetMs)
        segments += r.segments
        words += r.words
        AppLog.i(TAG, "Chunk @${offsetMs / 1000}s: ${r.words.size} words in ${System.currentTimeMillis() - t} ms")
    }

    // STEP 3: The quietest 50 ms between two positions (RMS), as a cut point.
    private fun quietestCut(data: FloatArray, from: Int, to: Int): Int {
        val win = sampleRate / 20
        var best = to
        var bestRms = Double.MAX_VALUE
        var i = from
        while (i + win <= minOf(to, data.size)) {
            var s = 0.0
            for (k in i until i + win) s += data[k] * data[k]
            val rms = sqrt(s / win)
            if (rms < bestRms) { bestRms = rms; best = i + win / 2 }
            i += win
        }
        return best.coerceIn(1, data.size)
    }

    private companion object {
        const val TAG = "Speech"
    }
}
