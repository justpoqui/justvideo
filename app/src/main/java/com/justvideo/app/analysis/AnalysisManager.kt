package com.justvideo.app.analysis

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.justvideo.app.analysis.model.HighlightSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// =====================================================================
// AnalysisManager.kt
// The app-wide "analysis desk": the Highlights screen asks it to analyze
// a video and watches its progress. AnalysisService does the work in the
// background (so it continues if you leave the app).
// =====================================================================

sealed interface AnalysisState {
    data object Idle : AnalysisState

    /** progress 0..1; etaMs = estimated time left (null until it can be estimated). */
    data class Running(val uri: Uri, val name: String, val progress: Float, val stage: String, val etaMs: Long?) : AnalysisState
    data class Done(val key: String, val uri: Uri, val name: String) : AnalysisState
    data class Failed(val message: String) : AnalysisState
}

class AnalysisManager(private val context: Context) {

    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    /** The job the service should run next. */
    internal var pending: Pair<Uri, HighlightSettings>? = null

    val isRunning get() = _state.value is AnalysisState.Running

    // STEP 1: Start analyzing (the service checks the cache first).
    fun start(uri: Uri, name: String, settings: HighlightSettings) {
        if (isRunning) return
        pending = uri to settings
        _state.value = AnalysisState.Running(uri, name, 0f, "Starting…", null)
        ContextCompat.startForegroundService(context, Intent(context, AnalysisService::class.java).setAction(AnalysisService.ACTION_START))
    }

    fun cancel() {
        context.startService(Intent(context, AnalysisService::class.java).setAction(AnalysisService.ACTION_CANCEL))
    }

    /** Show an already-analyzed video without running anything. */
    fun showCached(key: String, uri: Uri, name: String) {
        if (!isRunning) _state.value = AnalysisState.Done(key, uri, name)
    }

    fun reset() {
        if (!isRunning) _state.value = AnalysisState.Idle
    }

    internal fun report(s: AnalysisState) {
        _state.value = s
    }
}
