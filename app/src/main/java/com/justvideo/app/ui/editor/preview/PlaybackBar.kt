package com.justvideo.app.ui.editor.preview

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.media.player.PlaybackUi

// =====================================================================
// PlaybackBar.kt
// The strip under the preview: play/pause, current time / total time,
// and a scrub bar you can drag to jump through the video.
// (Step 5 adds scrubbing by dragging the timeline itself.)
// =====================================================================

@Composable
fun PlaybackBar(
    playback: PlaybackUi,
    enabled: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    // STEP 1: While your finger is on the bar, show the finger's position
    // instead of the player's, so the thumb doesn't jump back.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1)
    val shownMs = dragging?.let { (it * duration).toLong() } ?: playback.positionMs

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // STEP 2: Play/pause (48dp touch target).
        IconButton(onClick = onTogglePlay, enabled = enabled) {
            Icon(
                if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
            )
        }

        // STEP 3: "00:12.3 / 01:05.0"
        Text(
            "${TimeFormat.precise(shownMs)} / ${TimeFormat.precise(playback.durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(112.dp),
        )

        // STEP 4: Scrub bar. Seeking happens while you drag, so the picture
        // follows your finger.
        Slider(
            value = (shownMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { f ->
                dragging = f
                onSeek((f * duration).toLong())
            },
            onValueChangeFinished = { dragging = null },
            enabled = enabled,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}
