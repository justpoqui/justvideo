package com.justvideo.app.analysis.speech

import com.justvideo.app.analysis.model.Segment
import com.justvideo.app.analysis.model.Word

// =====================================================================
// TranscriptParser.kt
// Turns whisper_jni.cpp's text output into segments and words.
//
// whisper works in "tokens" (word pieces). A token starting with a space
// begins a new word ("▁hello" + "world" → one word, " there" → another).
// Pure code, unit-tested.
// =====================================================================

object TranscriptParser {

    data class Result(val segments: List<Segment>, val words: List<Word>)

    // STEP 1: Parse one chunk's output. [offsetMs] = where this chunk
    // starts in the whole video, so times become absolute.
    fun parse(raw: String, offsetMs: Long): Result {
        val segments = mutableListOf<Segment>()
        val words = mutableListOf<Word>()
        var current: Word? = null

        fun flush() {
            current?.let { w -> if (w.text.isNotBlank()) words += w.copy(text = w.text.trim()) }
            current = null
        }

        for (line in raw.lineSequence()) {
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "S" -> if (parts.size >= 4) {
                    flush()
                    val text = parts[3].trim()
                    if (text.isNotEmpty() && !isNoise(text)) {
                        segments += Segment(text, offsetMs + parts[1].toLong(), offsetMs + parts[2].toLong())
                    }
                }
                "T" -> if (parts.size >= 5) {
                    val t0 = offsetMs + parts[1].toLong()
                    val t1 = offsetMs + parts[2].toLong()
                    val p = parts[3].toFloatOrNull() ?: 1f
                    val text = parts[4]
                    // STEP 2: New word if the piece starts with a space (or is
                    // a bracketed sound like "[Laughter]").
                    val startsWord = text.startsWith(" ") || text.startsWith("[") || text.startsWith("(")
                    val cur = current
                    if (cur == null || startsWord) {
                        flush()
                        current = Word(text, t0, maxOf(t1, t0), p)
                    } else {
                        current = cur.copy(text = cur.text + text, endMs = maxOf(cur.endMs, t1), prob = minOf(cur.prob, p))
                    }
                }
            }
        }
        flush()
        return Result(segments, words)
    }

    /** Whisper marks silence/music like "[BLANK_AUDIO]" or "[Music]": not real sentences. */
    private fun isNoise(text: String) = text.matches(Regex("^[\\[(].*[\\])]$")) && !LAUGH.containsMatchIn(text)

    internal val LAUGH = Regex("(?i)(laugh|haha|hehe|lol|chuckl|giggl)")
}
