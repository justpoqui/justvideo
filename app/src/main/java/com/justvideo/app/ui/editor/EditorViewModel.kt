package com.justvideo.app.ui.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.data.repo.MediaRepository
import com.justvideo.app.data.repo.ProjectRepository
import com.justvideo.app.editor.TimelineOps
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
// edits and saves. The screen just shows `state` and reports taps back here.
// =====================================================================

class EditorViewModel(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val media: MediaRepository,
) : ViewModel() {

    private val projectId: String = savedState.toRoute<EditorRoute>().projectId

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    // STEP 1: One-off messages for the screen to show as snackbars.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // STEP 2: Saving. Every edit pushes the new timeline here, and a single
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

    // STEP 3: Load the project from the database.
    private suspend fun load() {
        val project = projects.load(projectId)
        _state.update {
            if (project == null) it.copy(isLoading = false, notFound = true)
            else it.copy(isLoading = false, projectName = project.name, timeline = project.timeline)
        }
    }

    // STEP 4: Videos/photos picked from the Photo Picker.
    fun onVisualMediaPicked(uris: List<Uri>) = import(uris, forcedKind = null) { tl, clips ->
        TimelineOps.appendVideo(tl, clips)
    }

    // STEP 5: Audio files picked from the file picker.
    fun onAudioPicked(uris: List<Uri>) = import(uris, forcedKind = MediaKind.AUDIO) { tl, clips ->
        TimelineOps.appendAudio(tl, clips)
    }

    // STEP 6: Shared import logic. Keep access to each file, read its
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

    // STEP 7: Tap a clip to select it, tap it again to deselect.
    fun onClipTapped(clipId: String) {
        _state.update { it.copy(selectedClipId = if (it.selectedClipId == clipId) null else clipId) }
    }

    fun deleteSelected() {
        val id = _state.value.selectedClipId ?: return
        edit { TimelineOps.delete(it, id) }
        _state.update { it.copy(selectedClipId = null) }
    }

    // STEP 8: Every edit goes through here: update the screen right away,
    // then queue a save.
    private fun edit(change: (Timeline) -> Timeline) {
        val updated = change(_state.value.timeline)
        _state.update { it.copy(timeline = updated) }
        pendingSave.value = updated
    }

    private companion object {
        const val TAG = "Editor"
    }
}
