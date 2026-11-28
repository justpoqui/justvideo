package com.justvideo.app.ui.editor

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.OverlayItem
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.data.model.Timeline

// =====================================================================
// EditorUiState.kt
// Everything the Editor screen needs to draw itself, in one object.
// The ViewModel produces it and the screen only reads it.
// =====================================================================

/** What kind of thing is selected (decides which tools the toolbar shows). */
enum class SelectionKind { NONE, VIDEO_CLIP, AUDIO_CLIP, TEXT, OVERLAY }

/** A slow tool that's running (captions, reframe, silence removal…). */
data class BusyTask(val label: String, val progress: Float?)

data class EditorUiState(
    val isLoading: Boolean = true,
    /** True if the project was deleted or couldn't be opened. */
    val notFound: Boolean = false,
    val projectName: String = "",
    val timeline: Timeline = Timeline(),
    /** Number of files still being read during an import (0 = not importing). */
    val importingCount: Int = 0,
    /** The selected item: a clip, text or overlay id (highlighted; tools act on it). */
    val selectedClipId: String? = null,
    /** Clips whose original file can't be found any more (deleted from the gallery). */
    val missingClipIds: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val busy: BusyTask? = null,
) {
    // STEP 1: Helpers to find the selected thing.
    val selectedClip: Clip?
        get() = selectedClipId?.let { id -> timeline.videoClips.firstOrNull { it.id == id } ?: timeline.audioClips.firstOrNull { it.id == id } }
    val selectedText: TextItem? get() = selectedClipId?.let { id -> timeline.texts.firstOrNull { it.id == id } }
    val selectedOverlay: OverlayItem? get() = selectedClipId?.let { id -> timeline.overlays.firstOrNull { it.id == id } }

    val selectionKind: SelectionKind
        get() = when {
            selectedText != null -> SelectionKind.TEXT
            selectedOverlay != null -> SelectionKind.OVERLAY
            selectedClip?.kind == MediaKind.AUDIO -> SelectionKind.AUDIO_CLIP
            selectedClip != null -> SelectionKind.VIDEO_CLIP
            else -> SelectionKind.NONE
        }
}
