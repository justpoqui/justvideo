package com.justvideo.app.media.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.justvideo.app.core.StorageCheck
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Timeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// =====================================================================
// ExportManager.kt
// The app-wide "export desk": screens hand it jobs and watch its state,
// and ExportService does the actual work in the background.
// Jobs wait in a queue, so batch exports (Auto Highlights) run one after
// another.
// =====================================================================

/** One video to render. */
data class ExportJob(
    val id: String,
    val title: String,
    val timeline: Timeline,
    val format: CanvasFormat,
    /** Base for the file name in the gallery. */
    val fileBase: String = "JustVideo",
)

sealed interface ExportState {
    data object Idle : ExportState

    /** progress 0..1; [index]/[total] for batches. */
    data class Running(val job: ExportJob, val progress: Float, val index: Int, val total: Int) : ExportState
    data class Done(val uris: List<Uri>, val failed: Int) : ExportState
    data class Failed(val message: String) : ExportState
}

class ExportManager(private val context: Context) {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    // STEP 1: The queue. ExportService takes jobs from here.
    private val queue = ArrayDeque<ExportJob>()
    internal var batchTotal = 0
    internal var batchIndex = 0
    internal val finished = mutableListOf<Uri>()
    internal var failedCount = 0

    val isBusy: Boolean get() = _state.value is ExportState.Running

    // STEP 2: Start exporting one or more videos. Returns an error message
    // instead if there isn't enough free storage.
    fun start(jobs: List<ExportJob>): String? {
        if (jobs.isEmpty()) return null
        if (isBusy) return "An export is already running"
        StorageCheck.checkForExport(context, jobs.sumOf { it.timeline.durationMs })?.let { return it }
        queue.clear(); queue.addAll(jobs)
        batchTotal = jobs.size; batchIndex = 0; finished.clear(); failedCount = 0
        _state.value = ExportState.Running(jobs.first(), 0f, 1, jobs.size)
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java).setAction(ExportService.ACTION_START))
        return null
    }

    fun cancel() {
        context.startService(Intent(context, ExportService::class.java).setAction(ExportService.ACTION_CANCEL))
    }

    /** Forget a finished/failed result (after the screen showed it). */
    fun acknowledge() {
        if (!isBusy) _state.value = ExportState.Idle
    }

    // STEP 3: Called by ExportService.
    internal fun nextJob(): ExportJob? = queue.removeFirstOrNull()?.also { batchIndex++ }
    internal fun clearQueue() = queue.clear()
    internal fun report(state: ExportState) {
        _state.value = state
    }
}
