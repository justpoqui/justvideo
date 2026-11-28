package com.justvideo.app.analysis.speech

import com.justvideo.app.analysis.model.Transcript

// =====================================================================
// SpeechSignals.kt
// Turns the transcript into one number per second for each speech
// signal: speech rate, excitement (!), questions (?), hype words, and
// laughter. Pure code, unit-tested.
// =====================================================================

class SpeechSignals(
    val speechRate: FloatArray,
    val excitement: FloatArray,
    val question: FloatArray,
    val hypeWords: FloatArray,
    val laughter: FloatArray,
) {
    companion object {
        // STEP 1: Built-in hype phrases (lower case, no punctuation).
        // Your own keywords from Settings are added to these.
        val DEFAULT_HYPE = listOf(
            "no way", "let's go", "lets go", "oh my god", "oh my gosh", "omg", "what the", "holy",
            "insane", "crazy", "unbelievable", "are you kidding", "wow", "yes", "boom", "clutch",
            "let's goo", "no no no", "wait what", "oh no", "huge", "massive", "incredible", "amazing",
            "i can't believe", "did you see", "look at that", "goal", "winner", "first time", "never",
        )

        private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

        // STEP 2: Build all signals for a video of [seconds] seconds.
        fun from(transcript: Transcript, seconds: Int, extraKeywords: List<String> = emptyList()): SpeechSignals {
            val rate = FloatArray(seconds)
            val excite = FloatArray(seconds)
            val question = FloatArray(seconds)
            val hype = FloatArray(seconds)
            val laugh = FloatArray(seconds)
            fun sec(ms: Long) = (ms / 1000).toInt().coerceIn(0, maxOf(seconds - 1, 0))
            if (seconds == 0) return SpeechSignals(rate, excite, question, hype, laugh)

            // Speech rate: words per second, by each word's middle.
            for (w in transcript.words) {
                if (TranscriptParser.LAUGH.containsMatchIn(w.text) && w.text.first() in "[(") {
                    for (s in sec(w.startMs)..sec(w.endMs)) laugh[s] = 1f
                    continue
                }
                rate[sec((w.startMs + w.endMs) / 2)] += 1f
                if (w.text.contains('!')) excite[sec(w.endMs)] = 1f
                if (w.text.contains('?')) question[sec(w.endMs)] = 1f
                if (Regex("(?i)^(ha){2,}|^(he){2,}|^lol").containsMatchIn(w.text.trim())) laugh[sec(w.startMs)] = 1f
            }

            // Hype phrases: slide over the words and match whole phrases
            // (1–4 words long), marking every second the phrase covers.
            val phrases = (DEFAULT_HYPE + extraKeywords).map(::norm).filter { it.isNotEmpty() }.toSet()
            val words = transcript.words
            val normWords = words.map { norm(it.text) }
            for (i in words.indices) {
                var text = ""
                for (len in 1..4) {
                    val j = i + len - 1
                    if (j >= words.size) break
                    text = if (len == 1) normWords[i] else "$text ${normWords[j]}"
                    if (text in phrases) {
                        for (s in sec(words[i].startMs)..sec(words[j].endMs)) hype[s] = minOf(hype[s] + 1f, 2f)
                    }
                }
            }
            return SpeechSignals(rate, excite, question, hype, laugh)
        }
    }
}
