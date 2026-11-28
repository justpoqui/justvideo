package com.justvideo.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justvideo.app.analysis.model.SpeechModel
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.ProjectSummary
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.data.repo.MediaRepository
import com.justvideo.app.data.repo.ProjectRepository
import com.justvideo.app.editor.BeatDetector
import com.justvideo.app.editor.ClipTools
import com.justvideo.app.editor.Templates
import com.justvideo.app.editor.TextAnimation
import com.justvideo.app.editor.TimelineOps
import com.justvideo.app.ui.create.CreateAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// =====================================================================
// HomeViewModel.kt
// Holds the Home screen's data (your drafts) and builds projects from
// the Create tab's templates. It survives screen rotation, and it does
// the database work off the main thread.
// =====================================================================

/** What the Home screen shows. `projects == null` means still loading. */
data class HomeUiState(val projects: List<ProjectSummary>? = null)

/** A template being built (shown as a progress popup). */
data class TemplateProgress(val label: String, val progress: Float?)

class HomeViewModel(
    private val projects: ProjectRepository,
    private val media: MediaRepository,
    private val tools: ClipTools,
    private val speechModel: () -> SpeechModel,
    private val speechReady: () -> Boolean,
) : ViewModel() {

    // STEP 1: Live list from the database, turned into screen state.
    val state: StateFlow<HomeUiState> = projects.observeProjects()
        .map { HomeUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _building = MutableStateFlow<TemplateProgress?>(null)
    val building: StateFlow<TemplateProgress?> = _building.asStateFlow()
    private var buildJob: Job? = null

    // STEP 2: Create a blank project, then open it. viewModelScope calls
    // back on the main thread, which is where navigation must happen.
    fun createProject(onCreated: (String) -> Unit, onFailed: (Exception) -> Unit) {
        viewModelScope.launch {
            try {
                onCreated(projects.createProject())
            } catch (e: Exception) {
                onFailed(e)
            }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { projects.rename(id, name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { projects.delete(id) }
    }

    /** Drafts: make a copy of a project to try a different edit. */
    fun duplicate(id: String) {
        viewModelScope.launch { projects.duplicate(id) }
    }

    // STEP 3: Build a project from a Create-tab template and open it.
    fun createFromTemplate(action: CreateAction, uris: List<Uri>, song: Uri?, onCreated: (String) -> Unit, onMessage: (String) -> Unit) {
        if (buildJob?.isActive == true) return
        buildJob = viewModelScope.launch {
            _building.value = TemplateProgress("Building your video…", null)
            try {
                // Read the picked files into clips.
                val clips = uris.mapNotNull { uri ->
                    media.keepAccess(uri)
                    media.probe(uri)?.let { TimelineOps.clipFrom(it) }
                }
                if (clips.isEmpty()) return@launch onMessage("Couldn't read those files")

                val timeline: Timeline = when (action) {
                    CreateAction.SLIDESHOW -> Templates.slideshow(clips)
                    CreateAction.PROMO -> Templates.promo(clips, "YOUR TITLE", "Follow for more")
                    CreateAction.TUTORIAL -> Templates.tutorial(clips)
                    CreateAction.MOTION -> Timeline(
                        videoClips = clips,
                        texts = listOf(Templates.animate(TextItem("title", "TAP TO EDIT", startMs = 0, durationMs = 3_000, size = 0.1f), TextAnimation.POP)),
                    )
                    CreateAction.MUSIC_VIDEO -> {
                        val songUri = song ?: return@launch onMessage("Pick a song too")
                        media.keepAccess(songUri)
                        val songClip = media.probe(songUri, MediaKind.AUDIO)?.let { TimelineOps.clipFrom(it) }
                            ?: return@launch onMessage("Couldn't read that song")
                        _building.value = TemplateProgress("Finding the beat…", 0f)
                        val energy = tools.loudness(songClip) { p -> _building.value = TemplateProgress("Finding the beat…", p) }
                        val beats = BeatDetector.detect(energy)
                        AppLog.i(TAG, "Beat: ${beats.bpm} BPM, ${beats.timesMs.size} beats")
                        Templates.beatSync(clips, songClip, beats.timesMs)
                    }
                    CreateAction.EXPLAINER -> explainer(clips, onMessage)
                    else -> Timeline(videoClips = clips)
                }
                val id = projects.createProject()
                projects.saveTimeline(id, timeline)
                projects.rename(id, nameFor(action))
                onCreated(id)
            } catch (e: Exception) {
                AppLog.e(TAG, "Template failed", e)
                onMessage("Couldn't build it: ${e.message}")
            } finally {
                _building.value = null
            }
        }
    }

    fun cancelTemplate() {
        buildJob?.cancel()
    }

    // Explainer: transcribe every talking clip, so captions are ready.
    private suspend fun explainer(clips: List<Clip>, onMessage: (String) -> Unit): Timeline {
        if (!speechReady()) {
            onMessage("Download the speech model (Auto Highlights → Settings) to get automatic captions")
            return Timeline(videoClips = clips)
        }
        val withCaptions = clips.mapIndexed { i, c ->
            if (c.kind != MediaKind.VIDEO || !c.hasAudio) c
            else c.copy(captions = tools.transcribe(c, speechModel()) { p -> _building.value = TemplateProgress("Writing captions ${i + 1}/${clips.size}…", p) })
        }
        return Timeline(videoClips = withCaptions, showCaptions = true)
    }

    private fun nameFor(a: CreateAction) = when (a) {
        CreateAction.SLIDESHOW -> "Slideshow"
        CreateAction.PROMO -> "Promo"
        CreateAction.TUTORIAL -> "Tutorial"
        CreateAction.MOTION -> "Motion title"
        CreateAction.MUSIC_VIDEO -> "Music video"
        CreateAction.EXPLAINER -> "Explainer"
        else -> "Project"
    } + " " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    private companion object {
        const val TAG = "Home"
    }
}
