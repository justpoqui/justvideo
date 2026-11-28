package com.justvideo.app.ui.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.data.repo.MediaRepository
import com.justvideo.app.data.repo.ProjectRepository
import com.justvideo.app.editor.TimelineOps
import com.justvideo.app.media.export.ExportJob
import com.justvideo.app.media.player.PlaybackUi
import com.justvideo.app.media.player.PreviewController
import com.justvideo.app.ui.navigation.EditorRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// =====================================================================
// EditorViewModel.kt
// The Editor's "brain": it loads the project, imports files, applies
// edits, saves, and owns the preview player. The screen just shows
// `state` / `playback` and reports taps back here.
// =====================================================================

class EditorViewModel(
    savedState: SavedStateHandle,
    appContext: Context,
    private val projects: ProjectRepository,
    private val media: MediaRepository,
) : ViewModel() {

    private val projectId: String = savedState.toRoute<EditorRoute>().projectId

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    // STEP 1: One-off messages for the screen to show as snackbars.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // STEP 2: The preview player. It lives here (not in the screen), so
    // rotating the phone doesn't restart playback.
    private val preview = PreviewController(appContext, viewModelScope) { msg ->
        _messages.trySend(msg)
    }
    val playback: StateFlow<PlaybackUi> = preview.ui

    /** The player to draw (null when there's nothing to show). It changes when the player is created or released. */
    private val _player = MutableStateFlow(preview.player)
    val player = _player.asStateFlow()

    // STEP 3: Saving. Every edit pushes the new timeline here, and a single
    // background loop writes it to the database in order. A StateFlow only
    // keeps the newest value, so after several quick edits we write just the
    // latest one, and the final state is never skipped.
    private val pendingSave = MutableStateFlow<Timeline?>(null)

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            pendingSave.collect { timeline ->
                if (timeline != null) {
                    try {
                        projects.saveTimeline(projectId, timeline)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Save failed", e)
                        _messages.send("Couldn't save: ${e.message}")
                    }
                }
            }
        }
    }

    // STEP 4: Load the project from the database, then hand it to the player.
    private suspend fun load() {
        val project = projects.load(projectId)
        _state.update {
            if (project == null) it.copy(isLoading = false, notFound = true)
            else it.copy(isLoading = false, projectName = project.name, timeline = project.timeline)
        }
        project?.let { refreshPreview(it.timeline) }
    }

    // STEP 5: Videos/photos picked from the Photo Picker.
    fun onVisualMediaPicked(uris: List<Uri>) = import(uris, forcedKind = null) { tl, clips ->
        TimelineOps.appendVideo(tl, clips)
    }

    // STEP 6: Audio files picked from the file picker.
    fun onAudioPicked(uris: List<Uri>) = import(uris, forcedKind = MediaKind.AUDIO) { tl, clips ->
        TimelineOps.appendAudio(tl, clips)
    }

    // STEP 7: Shared import logic. Keep access to each file, read its
    // details (on a background thread), turn it into clips, then add them
    // all in one edit so the timeline updates just once.
    private fun import(
        uris: List<Uri>,
        forcedKind: MediaKind?,
        add: (Timeline, List<Clip>) -> Timeline,
    ) {
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

    // STEP 8: Tap a clip to select it. Tapping it again, or tapping empty
    // space (null), deselects.
    fun onClipTapped(clipId: String?) {
        _state.update { it.copy(selectedClipId = if (clipId == null || it.selectedClipId == clipId) null else clipId) }
    }

    // STEP 8b: Split at the playhead: the selected clip if the playhead is
    // on it, otherwise the video clip under the playhead.
    fun splitAtPlayhead() {
        val at = preview.ui.value.positionMs
        val before = _state.value.timeline
        val after = TimelineOps.splitAt(before, at, _state.value.selectedClipId)
        if (after == before) {
            _messages.trySend("Move the playhead inside a clip to split it")
            return
        }
        edit { after }
    }

    /** Can Split do anything right now? (Used to enable the button.) */
    fun canSplit(positionMs: Long): Boolean =
        TimelineOps.canSplitAt(_state.value.timeline, positionMs, _state.value.selectedClipId)

    /** The timeline finished a trim, reorder or audio move. Save it. */
    fun commitEdit(newTimeline: Timeline) {
        if (newTimeline != _state.value.timeline) edit { newTimeline }
    }

    // STEP 8c: Canvas shape (9:16 / 16:9 / 1:1). The preview updates at once.
    fun setCanvas(format: CanvasFormat) = edit { it.copy(canvas = format) }

    /** The export job for this project in the chosen size (null if there's no video yet). */
    fun exportJob(format: CanvasFormat): ExportJob? {
        val tl = _state.value.timeline
        if (tl.videoClips.isEmpty()) return null
        return ExportJob(
            id = "${projectId.take(8)}_${System.currentTimeMillis()}",
            title = _state.value.projectName,
            timeline = tl,
            format = format,
            fileBase = _state.value.projectName.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40).ifBlank { "JustVideo" },
        )
    }

    fun deleteSelected() {
        val id = _state.value.selectedClipId ?: return
        edit { TimelineOps.delete(it, id) }
        _state.update { it.copy(selectedClipId = null) }
    }

    // STEP 9: Playback controls (forwarded to the player).
    fun togglePlay() = preview.togglePlay()
    fun seekTo(ms: Long) = preview.seekTo(ms)

    /** You started dragging or pinching the timeline: pause, like CapCut does. */
    fun onScrubStart() = preview.pause()

    /** Called when the app goes to the background: never keep playing unseen. */
    fun pausePlayback() = preview.pause()

    // STEP 10: Every edit goes through here: update the screen right away,
    // update the preview, then queue a save.
    private fun edit(change: (Timeline) -> Timeline) {
        val updated = change(_state.value.timeline)
        _state.update { it.copy(timeline = updated) }
        refreshPreview(updated)
        pendingSave.value = updated
    }

    private fun refreshPreview(timeline: Timeline) {
        preview.setTimeline(timeline)
        _player.value = preview.player
    }

    // STEP 11: The editor is closed for good, so free the video decoders.
    override fun onCleared() {
        preview.release()
    }

    private companion object {
        const val TAG = "Editor"
    }
}
