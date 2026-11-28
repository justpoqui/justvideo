package com.justvideo.app.ui.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.justvideo.app.analysis.model.SpeechModel
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.CaptionStyle
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.Keyframe
import com.justvideo.app.data.model.KeyframeMath
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.OverlayItem
import com.justvideo.app.data.model.OverlayKind
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.data.repo.MediaRepository
import com.justvideo.app.data.repo.ProjectRepository
import com.justvideo.app.editor.ClipTools
import com.justvideo.app.editor.SilenceCutter
import com.justvideo.app.editor.TimelineOps
import com.justvideo.app.media.export.ExportJob
import com.justvideo.app.media.player.PlaybackUi
import com.justvideo.app.media.player.PreviewController
import com.justvideo.app.ui.navigation.EditorRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// =====================================================================
// EditorViewModel.kt
// The Editor's "brain": it loads the project, imports files, applies
// edits (with undo/redo), runs the slower clip tools (captions,
// auto-reframe, silence removal), saves, and owns the preview player.
// The screen just shows `state` / `playback` and reports taps back here.
// =====================================================================

class EditorViewModel(
    savedState: SavedStateHandle,
    appContext: Context,
    private val projects: ProjectRepository,
    private val media: MediaRepository,
    private val tools: ClipTools,
    /** The speech model Settings chose (for captions). */
    private val speechModel: () -> SpeechModel,
) : ViewModel() {

    private val projectId: String = savedState.toRoute<EditorRoute>().projectId

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    // STEP 1: One-off messages for the screen to show as snackbars.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // STEP 2: The preview player. It lives here (not in the screen), so
    // rotating the phone doesn't restart playback.
    private val preview = PreviewController(appContext, viewModelScope) { msg -> _messages.trySend(msg) }
    val playback: StateFlow<PlaybackUi> = preview.ui
    private val _player = MutableStateFlow(preview.player)
    val player = _player.asStateFlow()

    // STEP 3: Undo/redo. Every edit stores the timeline before it.
    private val undoStack = ArrayDeque<Timeline>()
    private val redoStack = ArrayDeque<Timeline>()
    private var busyJob: Job? = null

    // STEP 4: Saving (see edit()). A StateFlow keeps only the newest value,
    // so after quick edits only the latest is written, never skipping the last.
    private val pendingSave = MutableStateFlow<Timeline?>(null)

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            pendingSave.collect { timeline ->
                if (timeline != null) {
                    try {
                        // NonCancellable: leaving the editor mid-save still finishes the save.
                        withContext(NonCancellable) { projects.saveTimeline(projectId, timeline) }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Save failed", e)
                        _messages.send("Couldn't save: ${e.message}")
                    }
                }
            }
        }
    }

    // STEP 5: Load the project, then check every file still exists.
    private suspend fun load() {
        val project = projects.load(projectId)
        _state.update {
            if (project == null) it.copy(isLoading = false, notFound = true)
            else it.copy(isLoading = false, projectName = project.name, timeline = project.timeline)
        }
        project?.let { refreshPreview(it.timeline) }
        if (project != null) {
            val clips = project.timeline.videoClips + project.timeline.audioClips
            val missingUris = clips.map { it.sourceUri }.distinct().filterNot { media.isReadable(it) }.toSet()
            val missing = clips.filter { it.sourceUri in missingUris }.map { it.id }.toSet()
            if (missing.isNotEmpty()) {
                AppLog.w(TAG, "${missing.size} clip(s) have missing files")
                _state.update { it.copy(missingClipIds = missing) }
            }
        }
    }

    fun removeMissingClips() {
        val missing = _state.value.missingClipIds
        edit { tl -> missing.fold(tl) { acc, id -> TimelineOps.delete(acc, id) } }
        _state.update { it.copy(missingClipIds = emptySet()) }
    }

    fun keepMissingClips() = _state.update { it.copy(missingClipIds = emptySet()) }

    // STEP 6: Importing media (main track, audio track, picture-in-picture).
    fun onVisualMediaPicked(uris: List<Uri>) = import(uris, forcedKind = null) { tl, clips -> TimelineOps.appendVideo(tl, clips) }
    fun onAudioPicked(uris: List<Uri>) = import(uris, forcedKind = MediaKind.AUDIO) { tl, clips -> TimelineOps.appendAudio(tl, clips) }

    private fun import(uris: List<Uri>, forcedKind: MediaKind?, add: (Timeline, List<Clip>) -> Timeline) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importingCount = it.importingCount + uris.size) }
            var failed = 0
            val clips = uris.mapNotNull { uri ->
                media.keepAccess(uri)
                val info = media.probe(uri, forcedKind)
                _state.update { it.copy(importingCount = (it.importingCount - 1).coerceAtLeast(0)) }
                if (info == null) failed++
                info?.let { TimelineOps.clipFrom(it) }
            }
            if (clips.isNotEmpty()) edit { add(it, clips) }
            AppLog.i(TAG, "Imported ${clips.size} file(s), $failed failed")
            if (failed > 0) _messages.send("$failed file(s) couldn't be read and were skipped")
        }
    }

    /** Picture-in-picture: a photo or video placed on top, at the playhead. */
    fun onOverlayPicked(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            media.keepAccess(uri)
            val info = media.probe(uri) ?: return@launch _messages.send("Couldn't read that file")
            val video = info.kind == MediaKind.VIDEO
            val item = OverlayItem(
                id = newId(), kind = if (video) OverlayKind.VIDEO else OverlayKind.IMAGE, source = uri.toString(),
                startMs = playhead(), durationMs = if (video) info.durationMs.coerceAtMost(5_000) else 3_000,
                sourceDurationMs = info.durationMs, width = info.width, height = info.height,
            )
            edit { TimelineOps.addOverlay(it, item) }
            select(item.id)
        }
    }

    fun addSticker(emoji: String) {
        val item = OverlayItem(id = newId(), kind = OverlayKind.STICKER, source = emoji, startMs = playhead(), scale = 0.25f)
        edit { TimelineOps.addOverlay(it, item) }
        select(item.id)
    }

    fun addText(item: TextItem) {
        edit { TimelineOps.addText(it, item.copy(id = newId(), startMs = playhead())) }
    }

    // STEP 7: Selection. Tapping the selected item again (or empty space) deselects.
    fun onClipTapped(id: String?) {
        _state.update { it.copy(selectedClipId = if (id == null || it.selectedClipId == id) null else id) }
    }

    private fun select(id: String?) = _state.update { it.copy(selectedClipId = id) }

    // STEP 8: Basic edits.
    fun splitAtPlayhead() {
        val before = _state.value.timeline
        val after = TimelineOps.splitAt(before, playhead(), _state.value.selectedClipId)
        if (after == before) _messages.trySend("Move the playhead inside a clip to split it") else edit { after }
    }

    fun canSplit(positionMs: Long) = TimelineOps.canSplitAt(_state.value.timeline, positionMs, _state.value.selectedClipId)

    fun commitEdit(newTimeline: Timeline) {
        if (newTimeline != _state.value.timeline) edit { newTimeline }
    }

    fun setCanvas(format: CanvasFormat) = edit { it.copy(canvas = format) }

    fun deleteSelected() {
        val id = _state.value.selectedClipId ?: return
        edit { TimelineOps.deleteAny(it, id) }
        select(null)
    }

    fun duplicateSelected() {
        val id = _state.value.selectedClipId ?: return
        edit { TimelineOps.duplicate(it, id) }
    }

    // STEP 9: Clip settings (speed, volume, fades, transition, crop, colour,
    // keyframes). [live] = while a slider is being dragged: it updates the
    // preview but only the final value becomes one undo step.
    fun updateSelectedClip(live: Boolean = false, f: (Clip) -> Clip) {
        val id = _state.value.selectedClipId ?: return
        edit(record = !live) { TimelineOps.updateClip(it, id, f) }
    }

    fun updateSelectedText(live: Boolean = false, f: (TextItem) -> TextItem) {
        val id = _state.value.selectedClipId ?: return
        edit(record = !live) { TimelineOps.updateText(it, id, f) }
    }

    fun updateSelectedOverlay(live: Boolean = false, f: (OverlayItem) -> OverlayItem) {
        val id = _state.value.selectedClipId ?: return
        edit(record = !live) { TimelineOps.updateOverlay(it, id, f) }
    }

    /** Remember the state before a slider drag, so the whole drag is one undo step. */
    fun beginLiveEdit() = pushUndo(_state.value.timeline)

    fun updateTimeline(live: Boolean = false, f: (Timeline) -> Timeline) = edit(record = !live, change = f)

    fun setCaptionStyle(s: CaptionStyle) = edit { it.copy(captionStyle = s) }
    fun setShowCaptions(on: Boolean) = edit { it.copy(showCaptions = on) }

    // STEP 9b: Keyframes (Phase 4): store the selected layer's current
    // place at the playhead.
    fun addKeyframeHere() {
        val s = _state.value
        val t = playhead()
        s.selectedText?.let { item ->
            val local = (t - item.startMs).coerceIn(0, item.durationMs)
            val now = KeyframeMath.at(item.x, item.y, 1f, item.keyframes, local)
            updateSelectedText { it.copy(keyframes = (it.keyframes.filter { k -> k.atMs != local } + Keyframe(local, now.x, now.y, now.scaleMul, now.opacity)).sortedBy { k -> k.atMs }) }
            return
        }
        s.selectedOverlay?.let { item ->
            val local = (t - item.startMs).coerceIn(0, item.durationMs)
            val now = KeyframeMath.at(item.x, item.y, item.opacity, item.keyframes, local)
            updateSelectedOverlay { it.copy(keyframes = (it.keyframes.filter { k -> k.atMs != local } + Keyframe(local, now.x, now.y, now.scaleMul, now.opacity)).sortedBy { k -> k.atMs }) }
            return
        }
        val clip = s.selectedClip ?: return _messages.trySend("Select a text, sticker or clip first").let {}
        val start = clipStart(clip)
        val local = (t - start).coerceIn(0, clip.durationMs)
        updateSelectedClip { c -> c.copy(keyframes = (c.keyframes.filter { it.atMs != local } + Keyframe(local, 0f, 0f, 1f, 1f)).sortedBy { it.atMs }) }
    }

    /** Move the selected layer while dragging it on the preview (x/y in canvas units). */
    fun moveSelectedLayer(x: Float, y: Float, scaleMul: Float, live: Boolean) {
        val s = _state.value
        when {
            s.selectedText != null -> updateSelectedText(live) { it.copy(x = x, y = y, size = (it.size * scaleMul).coerceIn(0.02f, 0.3f)) }
            s.selectedOverlay != null -> updateSelectedOverlay(live) { it.copy(x = x, y = y, scale = (it.scale * scaleMul).coerceIn(0.05f, 1.2f)) }
        }
    }

    // STEP 10: Slow tools, with progress and Cancel.
    private fun runBusy(label: String, block: suspend ((Float) -> Unit) -> Unit) {
        if (busyJob?.isActive == true) return
        busyJob = viewModelScope.launch {
            _state.update { it.copy(busy = BusyTask(label, null)) }
            preview.pause()
            try {
                block { p -> _state.update { s -> s.copy(busy = BusyTask(label, p)) } }
            } catch (e: CancellationException) {
                _messages.trySend("Cancelled")
            } catch (e: Exception) {
                AppLog.e(TAG, "$label failed", e)
                _messages.trySend("$label failed: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun cancelBusy() {
        busyJob?.cancel()
    }

    /** Auto-captions for every clip (or just the selected one). */
    fun generateCaptions() = runBusy("Generating captions") { progress ->
        val tl = _state.value.timeline
        val targets = tl.videoClips.filter { it.kind == MediaKind.VIDEO && it.hasAudio && (_state.value.selectedClip == null || it.id == _state.value.selectedClipId) }
        if (targets.isEmpty()) throw IllegalStateException("No clips with sound to caption")
        var done = 0
        var result = tl
        for (c in targets) {
            val words = tools.transcribe(c, speechModel()) { p -> progress((done + p) / targets.size) }
            result = TimelineOps.updateClip(result, c.id) { it.copy(captions = words) }
            done++
        }
        edit { _ -> result.copy(showCaptions = true) }
        _messages.trySend("Captions added. Tap Captions → Edit words to fix any mistakes")
    }

    /** Auto-reframe the selected clip: crop to fill the canvas and follow the face. */
    fun autoReframe() = runBusy("Auto-reframe") { progress ->
        val clip = _state.value.selectedClip ?: throw IllegalStateException("Select a video clip first")
        val (xs, startSec) = tools.faceTrack(clip, progress)
        updateSelectedClip { it.copy(transform = it.transform.copy(fill = true, autoReframe = true, reframeX = xs, reframeStartSec = startSec)) }
        _messages.trySend("Reframed to follow faces")
    }

    /** Phase 4: cut dead air out of the selected clip. */
    fun removeSilences() = runBusy("Removing silences") { progress ->
        val clip = _state.value.selectedClip ?: throw IllegalStateException("Select a clip first")
        val energy = tools.loudness(clip, progress)
        val keep = SilenceCutter.keepRanges(energy)
        if (keep.size <= 1) return@runBusy _messages.trySend("No long silences found").let {}
        edit { tl ->
            val pieces = keep.map { r ->
                clip.copy(id = newId(), trimStartMs = clip.trimStartMs + r.startMs, trimEndMs = (clip.trimStartMs + r.endMs).coerceAtMost(clip.trimEndMs))
            }
            if (clip.kind == MediaKind.AUDIO) {
                tl.copy(audioClips = tl.audioClips.filterNot { it.id == clip.id } + pieces.let { list ->
                    var at = clip.timelineOffsetMs
                    list.map { p -> p.copy(timelineOffsetMs = at).also { at += p.durationMs } }
                })
            } else {
                val i = tl.videoClips.indexOfFirst { it.id == clip.id }
                tl.copy(videoClips = tl.videoClips.toMutableList().apply { removeAt(i); addAll(i, pieces) })
            }
        }
        _messages.trySend("Removed ${keep.size - 1} silence(s)")
    }

    // STEP 11: Undo / redo.
    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.timeline)
        apply(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.timeline)
        apply(next)
    }

    private fun pushUndo(t: Timeline) {
        if (undoStack.lastOrNull() == t) return
        undoStack.addLast(t)
        while (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
    }

    // STEP 12: Export job for this project in the chosen size.
    fun exportJob(format: CanvasFormat): ExportJob? {
        val tl = _state.value.timeline
        if (tl.videoClips.isEmpty()) return null
        return ExportJob(
            id = "${projectId.take(8)}_${System.currentTimeMillis()}",
            title = _state.value.projectName, timeline = tl, format = format,
            fileBase = _state.value.projectName.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40).ifBlank { "JustVideo" },
        )
    }

    // STEP 13: Playback.
    fun togglePlay() = preview.togglePlay()
    fun seekTo(ms: Long) = preview.seekTo(ms)
    fun onScrubStart() = preview.pause()
    fun pausePlayback() = preview.pause()
    fun playhead() = preview.ui.value.positionMs

    fun clipStart(c: Clip): Long =
        if (c.kind == MediaKind.AUDIO) c.timelineOffsetMs
        else TimelineOps.videoStarts(_state.value.timeline).getOrElse(_state.value.timeline.videoClips.indexOfFirst { it.id == c.id }) { 0 }

    // STEP 14: Every edit goes through here: remember the old timeline for
    // undo (unless it's a live slider update), update the screen and the
    // preview, then queue a save.
    private fun edit(record: Boolean = true, change: (Timeline) -> Timeline) {
        val before = _state.value.timeline
        val updated = change(before)
        if (updated == before) return
        if (record) pushUndo(before)
        apply(updated)
    }

    private fun apply(t: Timeline) {
        _state.update { it.copy(timeline = t, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
        refreshPreview(t)
        pendingSave.value = t
    }

    private fun refreshPreview(timeline: Timeline) {
        preview.setTimeline(timeline)
        _player.value = preview.player
    }

    override fun onCleared() {
        preview.release()
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "Editor"
    }
}
