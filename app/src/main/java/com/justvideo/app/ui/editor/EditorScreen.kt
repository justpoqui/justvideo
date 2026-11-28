package com.justvideo.app.ui.editor

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justvideo.app.JustVideoApp
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.FilterType
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.editor.TimelineOps
import com.justvideo.app.ui.editor.panels.BusyDialog
import com.justvideo.app.ui.editor.panels.CaptionWordsDialog
import com.justvideo.app.ui.editor.panels.TextEditorDialog
import com.justvideo.app.ui.editor.preview.PlaybackBar
import com.justvideo.app.ui.editor.preview.PreviewPane
import com.justvideo.app.ui.editor.timeline.TimelineView
import com.justvideo.app.ui.editor.toolbar.EditorToolbar
import com.justvideo.app.ui.editor.toolbar.Tool
import com.justvideo.app.ui.export.ExportSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
// EditorScreen.kt
// CapCut-style layout: preview on top, play bar, zoomable timeline in the
// middle, tools at the bottom. This file arranges the parts and connects
// toolbar buttons to the ViewModel. Each part is its own file.
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit) {
    // STEP 1: ViewModel. createSavedStateHandle() hands it the project id.
    val app = LocalContext.current.applicationContext as JustVideoApp
    val c = app.container
    val vm: EditorViewModel = viewModel {
        EditorViewModel(
            createSavedStateHandle(), app, c.projectRepository, c.mediaRepository, c.clipTools,
            speechModel = { c.analysisStore.loadSettings().speechModel },
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Which popup is open.
    var canvasMenu by remember { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var sheetTool by rememberSaveable { mutableStateOf<Tool?>(null) }
    var textDialog by remember { mutableStateOf<Pair<TextItem, Boolean>?>(null) } // item, isNew
    var editCaptions by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pausePlayback() }

    // STEP 2: Pickers.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.onVisualMediaPicked(it) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.onAudioPicked(it) }
    val pickOverlay = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.onOverlayPicked(it) }
    // A ".cube" LUT is copied into the app so it keeps working.
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(context.filesDir, "luts/lut_${System.currentTimeMillis()}.cube").apply { parentFile?.mkdirs() }
                    context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    f.absolutePath
                }.getOrNull()
            }
            if (path == null || com.justvideo.app.media.effects.ColorEffects.loadCube(path) == null) snackbar.showSnackbar("That file isn't a valid .cube LUT")
            else vm.updateSelectedClip { it.copy(color = it.color.copy(filter = FilterType.LUT, lutPath = path)) }
        }
    }

    // STEP 3: What each toolbar button does.
    fun onTool(tool: Tool) {
        when (tool) {
            Tool.MEDIA -> pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            Tool.AUDIO -> pickAudio.launch(arrayOf("audio/*"))
            Tool.OVERLAY -> pickOverlay.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            Tool.TEXT -> textDialog = TextItem(id = "", text = "", startMs = 0) to true
            Tool.EDIT_TEXT, Tool.STYLE -> state.selectedText?.let { textDialog = it to false }
            Tool.SPLIT -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.splitAtPlayhead() }
            Tool.REFRAME -> vm.autoReframe()
            Tool.SILENCE -> vm.removeSilences()
            Tool.DUPLICATE -> vm.duplicateSelected()
            Tool.DELETE -> vm.deleteSelected()
            else -> if (tool.hasSheet()) { vm.pausePlayback(); sheetTool = tool }
        }
    }

    // STEP 4: Popups (missing files, export, tool panel, text, captions, busy).
    if (state.missingClipIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::keepMissingClips,
            title = { Text("Some files are missing") },
            text = { Text("${state.missingClipIds.size} clip(s) point to files that were deleted or moved. Remove them from the project? (Otherwise the preview and export may fail.)") },
            confirmButton = { TextButton(onClick = vm::removeMissingClips) { Text("Remove") } },
            dismissButton = { TextButton(onClick = vm::keepMissingClips) { Text("Keep") } },
        )
    }
    if (showExport) {
        LaunchedEffect(Unit) { vm.pausePlayback() }
        ExportSheet(
            manager = c.exportManager, makeJob = vm::exportJob, defaultFormat = state.timeline.canvas,
            durationMs = state.timeline.durationMs, onDismiss = { showExport = false },
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        )
    }
    sheetTool?.let { tool ->
        EditorToolSheet(
            tool = tool, state = state, vm = vm,
            speechModelReady = c.speechModels.isReady(c.analysisStore.loadSettings().speechModel),
            onPickLut = { pickLut.launch(arrayOf("*/*")) },
            onEditCaptionWords = { editCaptions = true },
            onDismiss = { sheetTool = null },
        )
    }
    textDialog?.let { (item, isNew) ->
        TextEditorDialog(item, isNew, onDismiss = { textDialog = null }, onDone = { edited ->
            if (isNew) vm.addText(edited) else vm.updateSelectedText { edited.copy(id = it.id, startMs = it.startMs) }
            textDialog = null
        })
    }
    if (editCaptions) {
        val target = state.selectedClip?.takeIf { it.captions.isNotEmpty() } ?: state.timeline.videoClips.firstOrNull { it.captions.isNotEmpty() }
        if (target == null) editCaptions = false
        else CaptionWordsDialog(target.captions, onDismiss = { editCaptions = false }, onSave = { words ->
            vm.updateTimeline { tl -> TimelineOps.updateClip(tl, target.id) { it.copy(captions = words) } }
            editCaptions = false
        })
    }
    state.busy?.let { BusyDialog(it.label, it.progress, vm::cancelBusy) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.projectName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    // Undo / redo.
                    IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
                    // Canvas shape picker, e.g. "9:16 ▾".
                    Box {
                        TextButton(onClick = { canvasMenu = true }) { Text("${state.timeline.canvas.label} ▾") }
                        DropdownMenu(expanded = canvasMenu, onDismissRequest = { canvasMenu = false }) {
                            CanvasFormat.entries.forEach { f ->
                                DropdownMenuItem(text = { Text("${f.label}  ·  ${f.use}") }, onClick = { canvasMenu = false; vm.setCanvas(f) })
                            }
                        }
                    }
                    Button(onClick = { showExport = true }, enabled = state.timeline.videoClips.isNotEmpty()) { Text("Export") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            EditorToolbar(
                selection = state.selectionKind,
                canSplit = remember(state.timeline, state.selectedClipId, playback.positionMs) { vm.canSplit(playback.positionMs) },
                onTool = ::onTool,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notFound -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This project no longer exists.")
                    TextButton(onClick = onBack) { Text("Go back") }
                }
                // STEP 5: The editor. Portrait: preview on top, timeline under it.
                // Landscape: preview left, timeline right.
                else -> {
                    val preview: @Composable (Modifier) -> Unit = { m ->
                        PreviewPane(
                            player = player, playback = playback, timeline = state.timeline, selectedId = state.selectedClipId,
                            onTogglePlay = vm::togglePlay, onSelectLayer = vm::onClipTapped,
                            onMoveLayer = vm::moveSelectedLayer, onMoveLayerStart = vm::beginLiveEdit, modifier = m,
                        )
                    }
                    val controls: @Composable () -> Unit = {
                        PlaybackBar(playback = playback, enabled = player != null, onTogglePlay = vm::togglePlay, onSeek = vm::seekTo)
                        if (state.importingCount > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TimelineView(
                            timeline = state.timeline, playheadMs = playback.positionMs, isPlaying = playback.isPlaying,
                            selectedClipId = state.selectedClipId, thumbnails = c.thumbnailLoader,
                            onScrub = vm::seekTo, onScrubStart = vm::onScrubStart, onClipTapped = vm::onClipTapped,
                            onEditCommit = vm::commitEdit,
                        )
                    }
                    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        Row(Modifier.fillMaxSize()) {
                            preview(Modifier.weight(1f).fillMaxHeight())
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { controls() }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            preview(Modifier.weight(1f))
                            controls()
                        }
                    }
                }
            }
        }
    }
}
