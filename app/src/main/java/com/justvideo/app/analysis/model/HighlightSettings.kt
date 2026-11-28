package com.justvideo.app.analysis.model

import kotlinx.serialization.Serializable

// =====================================================================
// HighlightSettings.kt
// Everything you can tune on the Auto Highlights settings screen:
// how much each signal counts, clip lengths, how many clips, your own
// keywords, and the speech/AI options. Presets (Gaming, Podcast, …) are
// just ready-made weight sets.
// =====================================================================

@Serializable
data class HighlightSettings(
    /** How much each signal counts (0 = ignore, 1 = normal, 2 = double). */
    val weights: Map<Signal, Float> = Preset.BALANCED.weights,
    val preset: Preset = Preset.BALANCED,
    val minClipMs: Long = 10_000,
    val maxClipMs: Long = 15_000,
    /** Start this long BEFORE the peak, so the clip has a setup. */
    val leadInMs: Long = 3_000,
    val topN: Int = 10,
    /** Extra words that should count as "hype" (e.g. your channel's catchphrases). */
    val keywords: List<String> = emptyList(),
    /** Transcribe speech (needs the one-time model download). */
    val useSpeech: Boolean = true,
    val speechModel: SpeechModel = SpeechModel.TINY_EN,
    /** Optional on-device LLM that rates transcript lines (off by default; needs a model file). */
    val useLlm: Boolean = false,
    val llmModelPath: String? = null,
    /** Burn TikTok-style captions into batch exports. */
    val burnCaptions: Boolean = true,
) {
    fun weight(s: Signal): Float = weights[s] ?: 0f
}

// STEP 1: Speech models (downloaded once from the whisper.cpp project).
@Serializable
enum class SpeechModel(val fileName: String, val label: String, val sizeMb: Int, val language: String) {
    TINY_EN("ggml-tiny.en-q5_1.bin", "Fast (English)", 31, "en"),
    BASE_EN("ggml-base.en-q5_1.bin", "Accurate (English)", 57, "en"),
    TINY("ggml-tiny-q5_1.bin", "Fast (any language)", 31, "auto"),
    BASE("ggml-base-q5_1.bin", "Accurate (any language)", 57, "auto");

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

// STEP 2: Presets: weight sets tuned for kinds of video.
@Serializable
enum class Preset(val label: String, val weights: Map<Signal, Float>) {
    BALANCED(
        "Balanced",
        mapOf(
            Signal.LOUDNESS to 1f, Signal.LOUD_JUMP to 1f, Signal.BURST to 1f, Signal.MUSIC_DROP to 0.6f,
            Signal.SPEECH_RATE to 0.8f, Signal.EXCITEMENT to 1f, Signal.QUESTION to 0.4f, Signal.HYPE to 1.2f,
            Signal.LAUGHTER to 1f, Signal.MOTION to 0.8f, Signal.SCENE_CUT to 0.4f, Signal.FACES to 0.4f,
            Signal.CLOSE_UP to 0.6f,
        ),
    ),
    GAMING(
        "Gaming",
        mapOf(
            Signal.LOUDNESS to 1.2f, Signal.LOUD_JUMP to 1.5f, Signal.BURST to 1.2f, Signal.MUSIC_DROP to 0.3f,
            Signal.SPEECH_RATE to 1f, Signal.EXCITEMENT to 1.3f, Signal.QUESTION to 0.2f, Signal.HYPE to 1.6f,
            Signal.LAUGHTER to 1f, Signal.MOTION to 1.2f, Signal.SCENE_CUT to 0.3f, Signal.FACES to 0.2f,
            Signal.CLOSE_UP to 0.3f,
        ),
    ),
    PODCAST(
        "Podcast",
        mapOf(
            Signal.LOUDNESS to 0.6f, Signal.LOUD_JUMP to 0.8f, Signal.BURST to 0.8f, Signal.MUSIC_DROP to 0f,
            Signal.SPEECH_RATE to 1.2f, Signal.EXCITEMENT to 1.2f, Signal.QUESTION to 1f, Signal.HYPE to 1.2f,
            Signal.LAUGHTER to 1.6f, Signal.MOTION to 0.2f, Signal.SCENE_CUT to 0.1f, Signal.FACES to 0.4f,
            Signal.CLOSE_UP to 0.8f,
        ),
    ),
    SPORTS(
        "Sports",
        mapOf(
            Signal.LOUDNESS to 1.4f, Signal.LOUD_JUMP to 1.6f, Signal.BURST to 1.6f, Signal.MUSIC_DROP to 0.2f,
            Signal.SPEECH_RATE to 0.6f, Signal.EXCITEMENT to 1f, Signal.QUESTION to 0f, Signal.HYPE to 1f,
            Signal.LAUGHTER to 0.3f, Signal.MOTION to 1.4f, Signal.SCENE_CUT to 0.6f, Signal.FACES to 0.1f,
            Signal.CLOSE_UP to 0.2f,
        ),
    ),
    VLOG(
        "Vlog",
        mapOf(
            Signal.LOUDNESS to 0.8f, Signal.LOUD_JUMP to 0.8f, Signal.BURST to 0.8f, Signal.MUSIC_DROP to 0.6f,
            Signal.SPEECH_RATE to 0.8f, Signal.EXCITEMENT to 1.2f, Signal.QUESTION to 0.6f, Signal.HYPE to 1.2f,
            Signal.LAUGHTER to 1.2f, Signal.MOTION to 0.8f, Signal.SCENE_CUT to 0.6f, Signal.FACES to 0.8f,
            Signal.CLOSE_UP to 1.2f,
        ),
    ),
}
