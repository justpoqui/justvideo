package com.justvideo.app.ui.highlights

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justvideo.app.analysis.llm.LlmRater
import com.justvideo.app.analysis.model.Preset
import com.justvideo.app.analysis.model.Signal
import com.justvideo.app.analysis.model.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
// HighlightSettingsScreen.kt
// Tune Auto Highlights: a preset, how much each signal counts, clip
// length, lead-in, how many clips, your keywords, the speech model, and
// the optional on-device LLM. Changes save immediately. "Apply to current
// video" re-scores the open results without re-analyzing.
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightSettingsScreen(onBack: () -> Unit) {
    // Shared by the Highlights and Settings screens (scoped to the activity).
    val vm: HighlightsViewModel = viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val s by vm.settings.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keywordText by remember { mutableStateOf(s.keywords.joinToString(", ")) }
    var copyingLlm by remember { mutableStateOf(false) }

    // STEP 1: Pick an LLM model file (MediaPipe .task, e.g. Gemma 1B). It's
    // copied into the app once, since MediaPipe needs a real file path.
    val pickLlm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        copyingLlm = true
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val f = LlmRater.modelFile(context).apply { parentFile?.mkdirs() }
                    context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    f.absolutePath
                }.getOrNull()
            }
            copyingLlm = false
            vm.updateSettings(s.copy(llmModelPath = path, useLlm = path != null))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Highlight settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (results != null) item {
                Button(onClick = { vm.rescore(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Apply to current video") }
            }

            // STEP 2: Presets.
            item { Header("Preset") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Preset.entries) { p ->
                        FilterChip(
                            selected = s.preset == p,
                            onClick = { vm.updateSettings(s.copy(preset = p, weights = p.weights)) },
                            label = { Text(p.label) },
                        )
                    }
                }
            }

            // STEP 3: Clip length, lead-in, number of clips.
            item { Header("Clips") }
            item {
                Text("Length ${s.minClipMs / 1000}–${s.maxClipMs / 1000} s", style = MaterialTheme.typography.bodyMedium)
                RangeSlider(
                    value = (s.minClipMs / 1000f)..(s.maxClipMs / 1000f),
                    onValueChange = { r -> vm.updateSettings(s.copy(minClipMs = (r.start * 1000).toLong(), maxClipMs = (r.endInclusive * 1000).toLong().coerceAtLeast((r.start * 1000).toLong() + 2_000))) },
                    valueRange = 5f..60f, steps = 54,
                )
                Text("Start ${s.leadInMs / 1000.0} s before the peak (setup)", style = MaterialTheme.typography.bodyMedium)
                Slider(value = s.leadInMs / 1000f, onValueChange = { vm.updateSettings(s.copy(leadInMs = (it * 2).toInt() * 500L)) }, valueRange = 0f..6f, steps = 11)
                Text("Number of clips: ${s.topN}", style = MaterialTheme.typography.bodyMedium)
                Slider(value = s.topN.toFloat(), onValueChange = { vm.updateSettings(s.copy(topN = it.toInt())) }, valueRange = 3f..30f, steps = 26)
            }

            // STEP 4: Signal weights.
            item { Header("What counts (weights)") }
            items(Signal.entries) { sig ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sig.label.replaceFirstChar { it.uppercase() }, modifier = Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = s.weight(sig),
                        onValueChange = { v -> vm.updateSettings(s.copy(weights = s.weights + (sig to (v * 10).toInt() / 10f))) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("%.1f".format(s.weight(sig)), modifier = Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall)
                }
            }

            // STEP 5: Your keywords.
            item { Header("Your hype words") }
            item {
                OutlinedTextField(
                    value = keywordText,
                    onValueChange = { t ->
                        keywordText = t
                        vm.updateSettings(s.copy(keywords = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                    },
                    label = { Text("Comma separated, e.g. clutch, gg, subscribe") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // STEP 6: Speech.
            item { Header("Speech") }
            item {
                SwitchRow("Transcribe speech", s.useSpeech) { vm.updateSettings(s.copy(useSpeech = it)) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SpeechModel.entries) { m ->
                        FilterChip(selected = s.speechModel == m, onClick = { vm.updateSettings(s.copy(speechModel = m)) }, label = { Text("${m.label} · ${m.sizeMb} MB") })
                    }
                }
                if (s.useSpeech && !vm.isModelReady(s)) TextButton(onClick = vm::downloadModel) { Text("Download ${s.speechModel.label}") }
            }

            // STEP 7: Optional LLM.
            item { Header("AI line rating (experimental, off by default)") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "A small on-device language model (e.g. Gemma 1B in MediaPipe .task format, which you download yourself) " +
                            "rates each clip's words. Slower, and needs a strong phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SwitchRow("Use AI rating", s.useLlm && s.llmModelPath != null) { on ->
                        if (on && s.llmModelPath == null) pickLlm.launch(arrayOf("*/*")) else vm.updateSettings(s.copy(useLlm = on))
                    }
                    Text(
                        when {
                            copyingLlm -> "Copying model…"
                            s.llmModelPath != null -> "Model: ${s.llmModelPath!!.substringAfterLast('/')}"
                            else -> "No model picked"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { pickLlm.launch(arrayOf("*/*")) }) { Text("Choose model file…") }
                }
            }
            item { HorizontalDivider() }
            item {
                TextButton(onClick = { vm.updateSettings(com.justvideo.app.analysis.model.HighlightSettings()) ; keywordText = "" }) { Text("Reset to defaults") }
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
