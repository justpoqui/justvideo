package com.justvideo.app.ui.highlights

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justvideo.app.JustVideoApp
import com.justvideo.app.analysis.AnalysisState
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.media.export.ExportState
import com.justvideo.app.ui.theme.Pink
import com.justvideo.app.ui.theme.Violet

// =====================================================================
// HighlightsScreen.kt
// Auto Highlights, in three states:
// 1. START: pick a long video (plus speech model status, recent videos)
// 2. ANALYZING: progress, stage, time left, Cancel (runs in background)
// 3. RESULTS: ranked clip cards → keep/discard, fine-tune, edit, export
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenProject: (String) -> Unit) {
    // Shared by the Highlights and Settings screens (scoped to the activity).
    val vm: HighlightsViewModel = viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val download by vm.modelDownload.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val thumbs = (LocalContext.current.applicationContext as JustVideoApp).container.thumbnailLoader
    val snackbar = remember { SnackbarHostState() }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // STEP 1: Pickers/permissions. Notifications let you see progress
    // outside the app (Android 13+ asks once).
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            vm.onVideoPicked(uri)
        }
    }

    // Load thumbnails for the visible clips.
    LaunchedEffect(results) { results?.let { r -> thumbs.want(r.clips.map { thumbKey(r.uri.toString(), it) }) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(results?.name ?: "Auto Highlights", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { if (results != null) vm.closeResults() else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Highlight settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            val r = results
            if (r != null && analysis !is AnalysisState.Running) {
                ExportBar(
                    keptCount = r.clips.count { it.kept == true },
                    burnCaptions = settings.burnCaptions,
                    onCaptions = { vm.updateSettings(settings.copy(burnCaptions = it)) },
                    export = export,
                    onExport = vm::exportKept,
                    onCancel = vm::cancelExport,
                    onDone = vm::acknowledgeExport,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val a = analysis
            val r = results
            when {
                a is AnalysisState.Running -> Analyzing(a, onCancel = vm::cancelAnalysis)
                r != null -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "${r.clips.size} clips from ${TimeFormat.duration(r.durationMs)} of video · tap a clip to preview",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (settings.useSpeech && !vm.isModelReady()) {
                            TextButton(onClick = vm::reanalyze) { Text("Analyzed without speech. Re-analyze with speech") }
                        }
                    }
                    if (r.clips.isEmpty()) item { Text("No highlights found. Try the Gaming or Vlog preset in Settings, or a longer video.") }
                    itemsIndexed(r.clips, key = { _, c -> c.id }) { i, h ->
                        HighlightCard(
                            rank = i + 1, h = h, uri = r.uri.toString(), thumbs = thumbs,
                            onKeep = { vm.setKept(h.id, it) },
                            onPreview = { previewId = h.id },
                            onEdit = { vm.openInEditor(h, onOpenProject) },
                        )
                    }
                }
                else -> StartPane(
                    failed = (a as? AnalysisState.Failed)?.message,
                    modelReady = vm.isModelReady(settings),
                    modelLabel = "${settings.speechModel.label}, ${settings.speechModel.sizeMb} MB",
                    useSpeech = settings.useSpeech,
                    downloading = download,
                    onDownload = vm::downloadModel,
                    onPick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    sessions = sessions.map { Triple(it.key, it.name, it.uri) to it.durationMs },
                    onOpenSession = { k, name, uri -> vm.openSession(k, uri, name) },
                    onDeleteSession = { vm.deleteSession(it) },
                )
            }
        }
    }

    // STEP 2: Clip preview + fine-tune.
    val r = results
    val h = r?.clips?.firstOrNull { it.id == previewId }
    if (r != null && h != null) {
        ClipPreviewDialog(
            uri = r.uri.toString(), h = h, videoDurationMs = r.durationMs,
            onSave = { s, e -> vm.setRange(h.id, s, e); previewId = null },
            onDismiss = { previewId = null },
        )
    }
}

// STEP 3: Start: big pick button, speech model card, recent videos.
@Composable
private fun StartPane(
    failed: String?,
    modelReady: Boolean,
    modelLabel: String,
    useSpeech: Boolean,
    downloading: Float?,
    onDownload: () -> Unit,
    onPick: () -> Unit,
    sessions: List<Pair<Triple<String, String, String>, Long>>,
    onOpenSession: (String, String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Pink, Violet)))
                    .clickable(onClick = onPick)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = Color.White)
                Text("Choose a long video", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "Up to 1–2 hours. Everything runs on your phone, offline.",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        failed?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (useSpeech) item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Speech recognition", style = MaterialTheme.typography.titleSmall)
                    when {
                        modelReady -> Text("Ready: $modelLabel ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        downloading != null -> {
                            Text("Downloading… ${(downloading * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { downloading }, modifier = Modifier.fillMaxWidth())
                        }
                        else -> {
                            Text(
                                "Finds hype words, questions and punchlines. One-time download ($modelLabel), then it works offline. " +
                                    "Without it, highlights use audio + visuals only.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = onDownload) { Text("Download speech model") }
                        }
                    }
                }
            }
        }
        if (sessions.isNotEmpty()) {
            item { Text("Analyzed videos", style = MaterialTheme.typography.titleMedium) }
            items(sessions, key = { it.first.first }) { (s, duration) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onOpenSession(s.first, s.second, s.third) }
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Pink)
                    Column(Modifier.weight(1f).padding(10.dp)) {
                        Text(s.second, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text(TimeFormat.duration(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDeleteSession(s.first) }) { Icon(Icons.Rounded.Delete, "Delete") }
                }
            }
        }
    }
}

// STEP 4: Analyzing: big progress, stage, time left, Cancel.
@Composable
private fun Analyzing(a: AnalysisState.Running, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Pink)
        Spacer(Modifier.height(8.dp))
        Text("Finding highlights in", style = MaterialTheme.typography.bodyMedium)
        Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { a.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Spacer(Modifier.height(8.dp))
        Text("${(a.progress * 100).toInt()}% · ${a.stage}", style = MaterialTheme.typography.bodyMedium)
        a.etaMs?.let { Text("About ${TimeFormat.duration(it)} left", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Text(
            "You can leave the app. It keeps going, and you'll get a notification when it's done.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.height(52.dp)) { Text("Cancel") }
    }
}

// STEP 5: Bottom bar: captions toggle + "Export kept (N)", or batch progress.
@Composable
private fun ExportBar(
    keptCount: Int,
    burnCaptions: Boolean,
    onCaptions: (Boolean) -> Unit,
    export: ExportState,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (export) {
                is ExportState.Running -> {
                    Text("Exporting clip ${export.index} of ${export.total} · ${(export.progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { export.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                is ExportState.Done -> {
                    Text("${export.uris.size} clip(s) saved to Gallery › Movies › JustVideo" + if (export.failed > 0) " (${export.failed} failed)" else "")
                    TextButton(onClick = onDone) { Text("OK") }
                }
                is ExportState.Failed -> {
                    Text(export.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDone) { Text("OK") }
                }
                ExportState.Idle -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Burn in captions", modifier = Modifier.weight(1f))
                        Switch(checked = burnCaptions, onCheckedChange = onCaptions)
                    }
                    Button(onClick = onExport, enabled = keptCount > 0, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(if (keptCount > 0) "Export $keptCount kept clip(s) · 9:16" else "Keep clips (✓) to export them")
                    }
                }
            }
        }
    }
}
