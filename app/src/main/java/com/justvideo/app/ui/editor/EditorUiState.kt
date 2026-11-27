package com.justvideo.app.ui.editor

import com.justvideo.app.data.model.Timeline

// =====================================================================
// EditorUiState.kt
// Everything the Editor screen needs to draw itself, in one object.
// The ViewModel produces it and the screen only reads it.
// =====================================================================

data class EditorUiState(
    val isLoading: Boolean = true,
    /** True if the project was deleted or couldn't be opened. */
    val notFound: Boolean = false,
    val projectName: String = "",
    val timeline: Timeline = Timeline(),
    /** Number of files still being read during an import (0 = not importing). */
    val importingCount: Int = 0,
    /** The clip you tapped (highlighted, and the target of Delete). */
    val selectedClipId: String? = null,
)
