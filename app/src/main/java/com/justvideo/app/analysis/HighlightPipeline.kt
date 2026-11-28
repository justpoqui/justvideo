package com.justvideo.app.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.justvideo.app.analysis.audio.AudioAnalyzer
import com.justvideo.app.analysis.audio.AudioStreamDecoder
import com.justvideo.app.analysis.clipping.ClipFinder
import com.justvideo.app.analysis.llm.LlmRater
import com.justvideo.app.analysis.model.Features
import com.justvideo.app.analysis.model.Highlight
import com.justvideo.app.analysis.model.HighlightSettings
import com.justvideo.app.analysis.model.Transcript
import com.justvideo.app.analysis.scoring.HighlightScorer
import com.justvideo.app.analysis.speech.SpeechAnalyzer
import com.justvideo.app.analysis.speech.SpeechModelManager
import com.justvideo.app.analysis.speech.SpeechSignals
import com.justvideo.app.analysis.speech.WhisperBridge
import com.justvideo.app.analysis.visual.FaceAnalyzer
import com.justvideo.app.analysis.visual.FrameSampler
import com.justvideo.app.analysis.visual.VisualSignals
import com.justvideo.app.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// =====================================================================
// HighlightPipeline.kt
// Runs the whole Auto Highlights analysis for one video:
//
//   ┌ pass A: soundtrack → AudioAnalyzer + SpeechAnalyzer (whisper.cpp)
//   └ pass B: keyframes  → VisualSignals + FaceAnalyzer (ML Kit)
//   (A and B run AT THE SAME TIME, since they use different parts of the phone)
//   → Features + Transcript (cached)  → score → clips → optional LLM re-rank
//
// Each analyzer is its own class, so any one can be tuned or swapped.
// =====================================================================

/** Everything measured about a video. Cached, so re-opening it is instant. */
@Serializable
data class AnalysisResult(val features: Features, val transcript: Transcript, val hadSpeech: Boolean)

class HighlightPipeline(private val context: Context, private val models: SpeechModelManager) {

    /** Progress report: overall 0..1 plus a short description of the current stage. */
    fun interface Progress {
        fun update(fraction: Float, stage: String)
    }

    // STEP 1: Measure everything (the slow part, minutes for long videos).
    suspend fun analyze(uri: Uri, settings: HighlightSettings, progress: Progress): AnalysisResult = coroutineScope {
        val durationMs = withContext(Dispatchers.IO) { durationOf(uri) }
        require(durationMs > 0) { "Couldn't read the video's length" }
        val seconds = ((durationMs + 999) / 1000).toInt()

        var audioP = 0f
        var videoP = 0f
        var stage = "Starting…"
        fun report() = progress.update(0.6f * audioP + 0.4f * videoP, stage)

        // Pass A: audio + speech.
        val audioJob = async(Dispatchers.Default) {
            val audio = AudioAnalyzer()
            val whisper = if (settings.useSpeech && models.isReady(settings.speechModel)) {
                WhisperBridge.load(models.file(settings.speechModel).absolutePath)
            } else {
                null
            }
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            val speech = whisper?.let { SpeechAnalyzer(it, settings.speechModel.language, threads) }
            // Cancel must also stop whisper in the middle of a chunk (native
            // code doesn't notice coroutine cancellation on its own).
            coroutineContext[Job]?.invokeOnCompletion { if (it != null) speech?.abort() }
            stage = if (speech != null) "Listening & transcribing" else "Listening"
            try {
                val hasAudio = AudioStreamDecoder(context, uri).decode(
                    onChunk = { chunk -> audio.consume(chunk); speech?.consume(chunk) },
                    onProgress = { audioP = it; report() },
                )
                if (!hasAudio) AppLog.w(TAG, "No audio track")
                Triple(audio.finish(seconds), speech?.finish() ?: Transcript(), speech != null)
            } finally {
                whisper?.close()
            }
        }

        // Pass B: visual.
        val videoJob = async(Dispatchers.Default) {
            val visual = VisualSignals(seconds)
            FaceAnalyzer().use { faces ->
                FrameSampler(context, uri).sample(
                    onFrame = { f ->
                        val r = runCatching { faces.analyze(f.faceGray, f.faceWidth, f.faceHeight, f.rotationDegrees) }
                            .getOrDefault(FaceAnalyzer.Result(0, 0f, 0.5f, 0.5f))
                        visual.addFrame(f.timeMs, f.tiny, r.count, r.biggestFraction, r.biggestCenterX)
                    },
                    onProgress = { videoP = it; report() },
                )
            }
            visual
        }

        val (audio, transcript, hadSpeech) = audioJob.await()
        val visual = videoJob.await()
        stage = "Scoring"; report()

        // STEP 2: Combine all signals into one Features object.
        val speech = SpeechSignals.from(transcript, seconds, settings.keywords)
        AnalysisResult(
            Features(
                durationMs = durationMs,
                loudness = audio.loudness, loudJump = audio.loudJump, burst = audio.burst,
                musicDrop = audio.musicDrop, energy100ms = audio.energy100ms,
                speechRate = speech.speechRate, excitement = speech.excitement, question = speech.question,
                hypeWords = speech.hypeWords, laughter = speech.laughter,
                motion = visual.motion, sceneCut = visual.sceneCut, faces = visual.faces,
                closeUp = visual.closeUp, faceCenterX = visual.faceCenterX,
            ),
            transcript,
            hadSpeech,
        )
    }

    // STEP 3: Score + cut clips (fast; re-run whenever Settings change).
    // Keyword changes need the speech signals rebuilt, which is cheap.
    fun rank(result: AnalysisResult, settings: HighlightSettings, llm: LlmRater? = null): List<Highlight> {
        val f = result.features
        val speech = SpeechSignals.from(result.transcript, f.seconds, settings.keywords)
        val features = f.copy(hypeWords = speech.hypeWords)
        val score = HighlightScorer.score(features, settings)
        var clips = ClipFinder.find(score, features, result.transcript, settings) { "h${it}_${System.nanoTime() % 100000}" }
        // Optional: the language model nudges the ranking (30% of the score).
        if (llm != null) {
            clips = clips.map { c ->
                val rating = llm.rate(c.snippet) ?: return@map c
                c.copy(score = (c.score * 0.7f + rating * 10 * 0.3f).toInt())
            }.sortedByDescending { it.score }
        }
        return clips
    }

    private fun durationOf(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    }

    private companion object {
        const val TAG = "Pipeline"
    }
}
