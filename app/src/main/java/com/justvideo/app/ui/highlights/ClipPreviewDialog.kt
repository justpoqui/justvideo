package com.justvideo.app.ui.highlights

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import com.justvideo.app.analysis.model.Highlight
import com.justvideo.app.core.TimeFormat

// =====================================================================
// ClipPreviewDialog.kt
// Watch a suggested clip, and fine-tune its start/end by dragging the
// two handles. The slider spans 10 s either side of the clip; the clip
// replays from the new start each time you let go.
// =====================================================================

@OptIn(UnstableApi::class)
@Composable
fun ClipPreviewDialog(
    uri: String,
    h: Highlight,
    videoDurationMs: Long,
    onSave: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // STEP 1: The handles' range: the clip ±10 s, inside the video.
    val minMs = (h.startMs - 10_000).coerceAtLeast(0)
    val maxMs = (h.endMs + 10_000).coerceAtMost(videoDurationMs.coerceAtLeast(h.endMs))
    var range by remember { mutableStateOf(h.startMs.toFloat()..h.endMs.toFloat()) }

    // STEP 2: A simple player that plays only the selected range, looping.
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    fun load(start: Long, end: Long) {
        player.setMediaItem(
            MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()
            ).build()
        )
        player.prepare(); player.play()
    }
    LaunchedEffect(Unit) { load(h.startMs, h.endMs) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${TimeFormat.precise(range.start.toLong())} – ${TimeFormat.precise(range.endInclusive.toLong())}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentFrame(
                    player = player,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).heightIn(max = 420.dp).background(Color.Black),
                )
                Text("Length ${((range.endInclusive - range.start) / 1000).toInt()} s · drag the handles to fine-tune", style = MaterialTheme.typography.bodySmall)
                // STEP 3: Two handles. When you let go, the clip replays from the new start.
                RangeSlider(
                    value = range,
                    onValueChange = { r -> if (r.endInclusive - r.start >= 2_000) range = r },
                    valueRange = minMs.toFloat()..maxMs.toFloat(),
                    onValueChangeFinished = { load(range.start.toLong(), range.endInclusive.toLong()) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(TimeFormat.duration(minMs), style = MaterialTheme.typography.labelSmall)
                    Text(TimeFormat.duration(maxMs), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(range.start.toLong(), range.endInclusive.toLong()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        modifier = Modifier.padding(0.dp),
    )
}
