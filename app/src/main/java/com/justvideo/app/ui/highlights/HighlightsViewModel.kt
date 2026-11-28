package com.justvideo.app.ui.highlights

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justvideo.app.JustVideoApp
import com.justvideo.app.analysis.AnalysisState
import com.justvideo.app.analysis.HighlightPipeline
import com.justvideo.app.analysis.model.Highlight
import com.justvideo.app.analysis.model.HighlightSettings
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.ClipTransform
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.media.captions.CaptionWord
import com.justvideo.app.media.export.ExportJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
// HighlightsViewModel.kt
// The brain of the Auto Highlights screens: picks/analyzes a video
// (via AnalysisManager), loads the clips, keeps your Keep/Discard and
// fine-tuning, opens clips in the editor, and starts batch exports.
// =====================================================================

/** The clips currently shown, for one analyzed video. */
data class HighlightsResults(val key: String, val uri: Uri, val name: String, val durationMs: Long, val clips: List<Highlight>)

class HighlightsViewModel(app: Application) : AndroidViewModel(app) {

    private val c = (app as JustVideoApp).container
    val analysis: StateFlow<AnalysisState> = c.analysisManager.state
    val sessions = c.analysisStore.sessions
    val modelDownload = c.speechModels.progress
    val export = c.exportManager.state

    private val _settings = MutableStateFlow(c.analysisStore.loadSettings())
    val settings: StateFlow<HighlightSettings> = _settings.asStateFlow()

    private val _results = MutableStateFlow<HighlightsResults?>(null)
    val results: StateFlow<HighlightsResults?> = _results.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        // STEP 1: When an analysis finishes (even if you left and came back), show its clips.
        viewModelScope.launch {
            analysis.collect { s -> if (s is AnalysisState.Done) open(s.key, s.uri, s.name) }
        }
    }

    fun isModelReady(s: HighlightSettings = _settings.value) = c.speechModels.isReady(s.speechModel)

    // STEP 2: You picked a video. Already analyzed? Show it at once; else analyze.
    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            c.mediaRepository.keepAccess(uri)
            val key = c.analysisStore.keyFor(uri)
            val name = c.analysisStore.displayName(uri)
            if (c.analysisStore.loadHighlights(key) != null && c.analysisStore.loadResult(key) != null) {
                c.analysisManager.showCached(key, uri, name)
            } else {
                c.analysisManager.start(uri, name, _settings.value)
            }
        }
    }

    fun cancelAnalysis() = c.analysisManager.cancel()

    /** Analyze again from scratch (e.g. after downloading the speech model). */
    fun reanalyze() {
        val r = _results.value ?: return
        viewModelScope.launch {
            c.analysisStore.delete(r.key)
            _results.value = null
            c.analysisManager.reset()
            c.analysisManager.start(r.uri, r.name, _settings.value)
        }
    }

    fun openSession(key: String, uri: String, name: String) {
        c.analysisManager.showCached(key, Uri.parse(uri), name)
        viewModelScope.launch { open(key, Uri.parse(uri), name) }
    }

    fun deleteSession(key: String) = viewModelScope.launch { c.analysisStore.delete(key) }

    fun closeResults() {
        _results.value = null
        c.analysisManager.reset()
    }

    private suspend fun open(key: String, uri: Uri, name: String) {
        val clips = c.analysisStore.loadHighlights(key) ?: return
        val duration = c.analysisStore.loadResult(key)?.features?.durationMs ?: clips.maxOfOrNull { it.endMs } ?: 0
        _results.value = HighlightsResults(key, uri, name, duration, clips)
    }

    // STEP 3: Keep / Discard / fine-tune. Saved straight away.
    fun setKept(id: String, kept: Boolean?) = updateClip(id) { it.copy(kept = kept) }

    fun setRange(id: String, startMs: Long, endMs: Long) = updateClip(id) { it.copy(startMs = startMs, endMs = endMs) }

    private fun updateClip(id: String, f: (Highlight) -> Highlight) {
        val r = _results.value ?: return
        val updated = r.copy(clips = r.clips.map { if (it.id == id) f(it) else it })
        _results.value = updated
        viewModelScope.launch { c.analysisStore.saveHighlights(r.key, r.uri, r.name, r.durationMs, updated.clips) }
    }

    // STEP 4: Settings. Saved, and current results are re-scored from the
    // cached measurements (no re-analysis needed).
    fun updateSettings(s: HighlightSettings) {
        _settings.value = s
        c.analysisStore.saveSettings(s)
    }

    fun rescore() {
        val r = _results.value ?: return
        viewModelScope.launch {
            val result = c.analysisStore.loadResult(r.key) ?: return@launch
            val clips = withContext(Dispatchers.Default) {
                HighlightPipeline(getApplication(), c.speechModels).rank(result, _settings.value)
            }
            c.analysisStore.saveHighlights(r.key, r.uri, r.name, r.durationMs, clips)
            _results.value = r.copy(clips = clips)
            _messages.send("Re-scored with your settings: ${clips.size} clips")
        }
    }

    fun downloadModel() {
        val s = _settings.value
        viewModelScope.launch {
            try {
                c.speechModels.download(s.speechModel)
                _messages.send("Speech model ready")
            } catch (e: Exception) {
                AppLog.e("Highlights", "Model download failed", e)
                _messages.send("Download failed: ${e.message}")
            }
        }
    }

    // STEP 5: A clip as a one-clip timeline, with its spoken words as captions
    // (in source time, as Clip.captions expects).
    private suspend fun timelineFor(r: HighlightsResults, h: Highlight, captions: Boolean): Timeline {
        val result = c.analysisStore.loadResult(r.key)
        val words = if (captions) {
            result?.transcript?.wordsBetween(h.startMs, h.endMs)?.map { CaptionWord(it.text, it.startMs, it.endMs) }.orEmpty()
        } else {
            emptyList()
        }
        // Auto-reframe: a landscape video becomes a vertical clip that follows
        // the face, using the face positions the analysis already measured.
        val info = c.mediaRepository.probe(r.uri)
        val faceX = result?.features?.faceCenterX ?: FloatArray(0)
        val startSec = (h.startMs / 1000).toInt()
        val endSec = ((h.endMs + 999) / 1000).toInt().coerceAtMost(faceX.size)
        val reframe = if (startSec < endSec) faceX.copyOfRange(startSec, endSec).toList() else emptyList()
        val clip = Clip(
            id = h.id, sourceUri = r.uri.toString(), kind = MediaKind.VIDEO, displayName = r.name,
            sourceDurationMs = r.durationMs, trimStartMs = h.startMs, trimEndMs = h.endMs, hasAudio = true,
            width = info?.width ?: 0, height = info?.height ?: 0, rotationDegrees = info?.rotationDegrees ?: 0,
            captions = words,
            transform = ClipTransform(fill = true, autoReframe = reframe.isNotEmpty(), reframeX = reframe, reframeStartSec = startSec),
        )
        return Timeline(videoClips = listOf(clip), canvas = CanvasFormat.VERTICAL, showCaptions = captions)
    }

    /** "Open in editor": makes a new project from the clip, returns its id. */
    fun openInEditor(h: Highlight, onCreated: (String) -> Unit) {
        val r = _results.value ?: return
        viewModelScope.launch {
            val id = c.projectRepository.createProject()
            c.projectRepository.saveTimeline(id, timelineFor(r, h, captions = true))
            c.projectRepository.rename(id, "Highlight ${com.justvideo.app.core.TimeFormat.duration(h.startMs)}")
            onCreated(id)
        }
    }

    // STEP 6: Batch export every kept clip as a vertical 9:16 MP4.
    fun exportKept() {
        val r = _results.value ?: return
        val kept = r.clips.filter { it.kept == true }
        if (kept.isEmpty()) {
            _messages.trySend("Tap ✓ Keep on the clips you want first"); return
        }
        viewModelScope.launch {
            val jobs = kept.mapIndexed { i, h ->
                ExportJob(
                    id = "${r.key.take(6)}_${i}_${System.currentTimeMillis()}",
                    title = "Highlight ${i + 1}",
                    timeline = timelineFor(r, h, _settings.value.burnCaptions),
                    format = CanvasFormat.VERTICAL,
                    fileBase = "Highlight_${i + 1}",
                )
            }
            c.exportManager.start(jobs)?.let { _messages.send(it) }
        }
    }

    fun cancelExport() = c.exportManager.cancel()
    fun acknowledgeExport() = c.exportManager.acknowledge()
}
