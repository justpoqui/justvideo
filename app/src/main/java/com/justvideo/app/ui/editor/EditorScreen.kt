package com.justvideo.app.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justvideo.app.JustVideoApp
import com.justvideo.app.ui.editor.preview.PlaybackBar
import com.justvideo.app.ui.editor.preview.PreviewPane
import com.justvideo.app.ui.editor.timeline.TimelineView
import com.justvideo.app.ui.editor.toolbar.EditorToolbar

// =====================================================================
// EditorScreen.kt
// CapCut-style layout: preview on top, play bar, zoomable timeline in the middle,
// tools at the bottom. This file only arranges the parts. Each part is
// its own file.
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit) {
    // STEP 1: ViewModel. createSavedStateHandle() hands it the project id
    // from the navigation route.
    val app = LocalContext.current.applicationContext as JustVideoApp
    val vm: EditorViewModel = viewModel {
        EditorViewModel(
            createSavedStateHandle(),
            app,
            app.container.projectRepository,
            app.container.mediaRepository,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    // STEP 2: Show the ViewModel's messages as snackbars.
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // STEP 3: Pause when the app leaves the screen (home button, a call,
    // screen off).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pausePlayback() }

    // STEP 4: Pickers.
    // - Photo Picker: Android's built-in gallery picker for videos + photos.
    //   It needs no permission. On older phones Google Play services
    //   provides it automatically.
    // - File picker (OpenMultipleDocuments) for audio, because the Photo
    //   Picker doesn't show audio files.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        vm.onVisualMediaPicked(it)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.onAudioPicked(it)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.projectName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    // Export is built in step 7.
                    Button(onClick = {}, enabled = false) { Text("Export") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            EditorToolbar(
                canDelete = state.selectedClipId != null,
                canSplit = remember(state.timeline, state.selectedClipId, playback.positionMs) { vm.canSplit(playback.positionMs) },
                onAddMedia = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onAddAudio = { pickAudio.launch(arrayOf("audio/*")) },
                onSplit = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.splitAtPlayhead()
                },
                onDelete = vm::deleteSelected,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                // STEP 5: Loading / missing project.
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notFound -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This project no longer exists.")
                    TextButton(onClick = onBack) { Text("Go back") }
                }
                // STEP 6: The editor. The preview gets the spare space, with
                // the play bar and timeline under it.
                else -> Column(Modifier.fillMaxSize()) {
                    PreviewPane(
                        player = player,
                        playback = playback,
                        isEmpty = state.timeline.isEmpty,
                        onTogglePlay = vm::togglePlay,
                        modifier = Modifier.weight(1f),
                    )
                    PlaybackBar(
                        playback = playback,
                        enabled = player != null,
                        onTogglePlay = vm::togglePlay,
                        onSeek = vm::seekTo,
                    )
                    if (state.importingCount > 0) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    // The zoomable timeline (drag = scrub, pinch = zoom, tap = select).
                    TimelineView(
                        timeline = state.timeline,
                        playheadMs = playback.positionMs,
                        isPlaying = playback.isPlaying,
                        selectedClipId = state.selectedClipId,
                        thumbnails = app.container.thumbnailLoader,
                        onScrub = vm::seekTo,
                        onScrubStart = vm::onScrubStart,
                        onClipTapped = vm::onClipTapped,
                        onEditCommit = vm::commitEdit,
                    )
                }
            }
        }
    }
}
