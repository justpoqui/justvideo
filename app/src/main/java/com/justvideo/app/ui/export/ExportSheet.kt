package com.justvideo.app.ui.export

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.media.export.ExportJob
import com.justvideo.app.media.export.ExportManager
import com.justvideo.app.media.export.ExportState

// =====================================================================
// ExportSheet.kt
// The panel that slides up when you tap Export:
// 1. pick a size (9:16 / 16:9 / 1:1), 2. watch progress (or cancel),
// 3. done: Share or Open the video.
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    manager: ExportManager,
    /** Builds the job for the chosen format (null = nothing to export). */
    makeJob: (CanvasFormat) -> ExportJob?,
    defaultFormat: CanvasFormat,
    durationMs: Long,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()
    var format by rememberSaveable { mutableStateOf(defaultFormat) }

    // STEP 1: Start, after asking for any permission that's needed.
    // - Android 13+: notifications, for the progress bar (optional, and
    //   the export still runs if you say no).
    // - Android 8–9 only: storage, to save into the gallery.
    fun startNow() {
        val job = makeJob(format) ?: return onMessage("Add a video or photo first")
        manager.start(listOf(job))?.let(onMessage)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startNow() else onMessage("Storage permission is needed to save to the gallery on this Android version")
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (needsStoragePermission(context)) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else startNow()
    }
    fun requestAndStart() = when {
        Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS) ->
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        needsStoragePermission(context) -> storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> startNow()
    }

    ModalBottomSheet(onDismissRequest = {
        manager.acknowledge(); onDismiss()
    }) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                // STEP 2: Running: progress bar and Cancel. You can close this
                // panel or the app; the export keeps going.
                is ExportState.Running -> {
                    Text("Exporting…", style = MaterialTheme.typography.titleLarge)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        (if (s.total > 1) "Clip ${s.index} of ${s.total} · " else "") + "${(s.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "You can leave the app. Progress is in your notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = manager::cancel, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Cancel export") }
                }

                // STEP 3: Done: Share / Open.
                is ExportState.Done -> {
                    val uri = s.uris.firstOrNull()
                    Text(if (uri != null) "Saved to your gallery" else "Nothing was exported", style = MaterialTheme.typography.titleLarge)
                    if (uri != null) {
                        Text("Gallery › Movies › JustVideo", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { share(context, uri) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Share") }
                            OutlinedButton(onClick = { open(context, uri) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Open") }
                        }
                    }
                    OutlinedButton(onClick = { manager.acknowledge(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }

                is ExportState.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        "If this keeps happening, open Home ⋮ → Diagnostics and send me the log.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { manager.acknowledge() }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }

                // STEP 4: Choose a size, then Export.
                ExportState.Idle -> {
                    Text("Export", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Length ${TimeFormat.duration(durationMs)} · MP4 (H.264), hardware encoded",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CanvasFormat.entries.forEach { f ->
                        FormatRow(f, selected = f == format, onClick = { format = f })
                    }
                    Button(onClick = ::requestAndStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Export ${format.label} · ${format.width}×${format.height}")
                    }
                }
            }
        }
    }
}

// One size option, a big touch target with a highlighted border when picked.
@Composable
private fun FormatRow(f: CanvasFormat, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(f.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.25f))
        Column(Modifier.weight(0.75f)) {
            Text("${f.width} × ${f.height}", style = MaterialTheme.typography.bodyMedium)
            Text(f.use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun needsStoragePermission(context: Context) =
    Build.VERSION.SDK_INT <= 28 && !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share video"))
}

private fun open(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
