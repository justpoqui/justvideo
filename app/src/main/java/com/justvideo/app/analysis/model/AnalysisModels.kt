package com.justvideo.app.analysis.model

import kotlinx.serialization.Serializable

// =====================================================================
// AnalysisModels.kt
// The data Auto Highlights works with:
// - Transcript: what was said, with word timings (from whisper.cpp)
// - Features: one number per SECOND for every signal (loudness, motion…)
// - Highlight: a finished clip suggestion
// All @Serializable, so results can be cached and re-opened instantly.
// =====================================================================

// STEP 1: Speech.
@Serializable
data class Word(val text: String, val startMs: Long, val endMs: Long, val prob: Float = 1f)

@Serializable
data class Segment(val text: String, val startMs: Long, val endMs: Long)

@Serializable
data class Transcript(val segments: List<Segment> = emptyList(), val words: List<Word> = emptyList()) {
    /** All text between two times (for clip snippets and captions). */
    fun textBetween(startMs: Long, endMs: Long): String =
        words.filter { it.startMs >= startMs && it.endMs <= endMs }.joinToString(" ") { it.text.trim() }
            .replace(Regex("\\s+([,.!?])"), "$1")

    fun wordsBetween(startMs: Long, endMs: Long): List<Word> = words.filter { it.endMs > startMs && it.startMs < endMs }
}

// STEP 2: Every signal we score, one value per second (index = second).
// Raw values are kept (not normalized), so changing the weights in
// Settings re-scores instantly without re-analyzing the video.
@Serializable
data class Features(
    val durationMs: Long,
    // Audio
    val loudness: FloatArray,      // dBFS per second
    val loudJump: FloatArray,      // rise vs. the previous seconds (dB)
    val burst: FloatArray,         // noisy broadband energy (cheering, laughter, applause)
    val musicDrop: FloatArray,     // sudden bass energy after a quieter build-up
    /** Loudness every 100 ms (dBFS), used to find quiet gaps for clean cuts. */
    val energy100ms: FloatArray,
    // Speech
    val speechRate: FloatArray,    // words per second
    val excitement: FloatArray,    // exclamations
    val question: FloatArray,
    val hypeWords: FloatArray,     // "no way", "let's go"… plus your keywords
    val laughter: FloatArray,
    // Visual
    val motion: FloatArray,        // average frame difference (0..1)
    val sceneCut: FloatArray,      // 1 = a cut happened in this second
    val faces: FloatArray,         // number of faces seen
    val closeUp: FloatArray,       // size of the biggest face (fraction of the frame)
) {
    val seconds: Int get() = loudness.size

    // FloatArray has no built-in equality, so data-class equals is defined by hand.
    override fun equals(other: Any?) = other is Features && durationMs == other.durationMs && loudness.contentEquals(other.loudness)
    override fun hashCode() = durationMs.hashCode() * 31 + loudness.contentHashCode()
}

// STEP 3: What one signal contributed to a clip (for the "why picked" text).
@Serializable
data class Reason(val signal: Signal, val strength: Float)

/** The signals, with the short label shown on clip cards. */
@Serializable
enum class Signal(val label: String) {
    LOUDNESS("loud moment"),
    LOUD_JUMP("sudden volume jump"),
    BURST("cheering/laughter-like burst"),
    MUSIC_DROP("music drop"),
    SPEECH_RATE("fast speech"),
    EXCITEMENT("excited speech"),
    QUESTION("question"),
    HYPE("hype words"),
    LAUGHTER("laughter"),
    MOTION("lots of motion"),
    SCENE_CUT("scene changes"),
    FACES("faces on screen"),
    CLOSE_UP("close-up"),
}

// STEP 4: A finished clip suggestion.
@Serializable
data class Highlight(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val peakMs: Long,
    /** 0..100, higher is better. */
    val score: Int,
    val reasons: List<Reason>,
    val snippet: String,
    /** You tapped Keep (true) / Discard (false) / not decided yet (null). */
    val kept: Boolean? = null,
) {
    val durationMs: Long get() = endMs - startMs
    val whyText: String get() = reasons.take(3).joinToString(" + ") { it.signal.label }
}
