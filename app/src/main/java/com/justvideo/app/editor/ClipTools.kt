package com.justvideo.app.editor

import android.content.Context
import android.net.Uri
import com.justvideo.app.analysis.audio.AudioAnalyzer
import com.justvideo.app.analysis.audio.AudioStreamDecoder
import com.justvideo.app.analysis.model.SpeechModel
import com.justvideo.app.analysis.speech.SpeechAnalyzer
import com.justvideo.app.analysis.speech.SpeechModelManager
import com.justvideo.app.analysis.speech.WhisperBridge
import com.justvideo.app.analysis.visual.FaceAnalyzer
import com.justvideo.app.analysis.visual.FrameSampler
import com.justvideo.app.data.model.Clip
import com.justvideo.app.media.captions.CaptionWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

// =====================================================================
// ClipTools.kt
// Editor tools that look INSIDE a clip (only its trimmed part, so they're
// quick even for clips from long videos):
// - captions:   speech → timed words (whisper.cpp)
// - reframe:    where the face is each second (ML Kit), for 16:9 → 9:16
// - loudness:   every 100 ms, for "Remove silences" and beat detection
// =====================================================================

class ClipTools(private val context: Context, private val models: SpeechModelManager) {

    // STEP 1: Captions for one clip, in SOURCE time (as Clip.captions expects).
    suspend fun transcribe(clip: Clip, model: SpeechModel, onProgress: (Float) -> Unit): List<CaptionWord> = withContext(Dispatchers.Default) {
        if (!models.isReady(model)) throw IOException("Download the speech model first (Auto Highlights → Settings)")
        val whisper = WhisperBridge.load(models.file(model).absolutePath) ?: throw IOException("Speech engine unavailable")
        try {
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            val speech = SpeechAnalyzer(whisper, model.language, threads, startOffsetMs = clip.trimStartMs)
            coroutineContext[Job]?.invokeOnCompletion { if (it != null) speech.abort() }
            AudioStreamDecoder(context, Uri.parse(clip.sourceUri), clip.trimStartMs, clip.trimEndMs)
                .decode(onChunk = { speech.consume(it) }, onProgress = onProgress)
            speech.finish().words
                .filter { it.endMs > clip.trimStartMs && it.startMs < clip.trimEndMs }
                .map { CaptionWord(it.text, it.startMs, it.endMs) }
        } finally {
            whisper.close()
        }
    }

    // STEP 2: Face position per source second (0..1, 0.5 if none) for auto-reframe.
    // Returns (positions, first second).
    suspend fun faceTrack(clip: Clip, onProgress: (Float) -> Unit): Pair<List<Float>, Int> = withContext(Dispatchers.Default) {
        val startSec = (clip.trimStartMs / 1000).toInt()
        val endSec = ((clip.trimEndMs + 999) / 1000).toInt()
        val xs = FloatArray(endSec - startSec + 1) { Float.NaN }
        FaceAnalyzer().use { faces ->
            FrameSampler(context, Uri.parse(clip.sourceUri), 500, clip.trimStartMs, clip.trimEndMs).sample(
                onFrame = { f ->
                    val r = runCatching { faces.analyze(f.faceGray, f.faceWidth, f.faceHeight, f.rotationDegrees) }.getOrNull()
                    val i = (f.timeMs / 1000).toInt() - startSec
                    if (r != null && r.count > 0 && i in xs.indices) xs[i] = r.biggestCenterX
                },
                onProgress = onProgress,
            )
        }
        // Fill seconds with no face from their neighbours, then smooth, so the
        // camera glides instead of jumping.
        var last = 0.5f
        for (i in xs.indices) if (xs[i].isNaN()) xs[i] = last else last = xs[i]
        val smooth = List(xs.size) { i ->
            var s = 0f; var c = 0
            for (k in i - 2..i + 2) if (k in xs.indices) { s += xs[k]; c++ }
            s / c
        }
        smooth to startSec
    }

    // STEP 3: Loudness every 100 ms over the clip (dBFS).
    suspend fun loudness(clip: Clip, onProgress: (Float) -> Unit): FloatArray = withContext(Dispatchers.Default) {
        val a = AudioAnalyzer()
        AudioStreamDecoder(context, Uri.parse(clip.sourceUri), clip.trimStartMs, clip.trimEndMs)
            .decode(onChunk = { a.consume(it) }, onProgress = onProgress)
        a.finish(((clip.sourceSpanMs + 999) / 1000).toInt()).energy100ms
    }
}
