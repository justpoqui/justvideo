package com.justvideo.app.ui.editor.preview

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.justvideo.app.media.player.PlaybackUi

// =====================================================================
// PreviewPane.kt
// The video preview at the top of the editor. It shows the player's
// picture, letterboxed to fit. Tap the picture to play or pause.
// =====================================================================

@OptIn(UnstableApi::class)
@Composable
fun PreviewPane(
    player: Player?,
    playback: PlaybackUi,
    isEmpty: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // no ripple over the video
                enabled = player != null,
                onClick = onTogglePlay,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (player == null) {
            // STEP 1: Nothing to play yet: show a hint.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (isEmpty) "Add a video, photo or audio file with the tools below"
                    else "Add a video or photo to see the preview",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            // STEP 2: The video picture. ContentFrame keeps the right aspect
            // ratio (Fit = whole frame visible, black bars where needed).
            // SurfaceView is the most battery-friendly way to show video.
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = ContentScale.Fit,
            )

            // STEP 3: Big play icon while paused, spinner while loading.
            when {
                playback.isBuffering -> CircularProgressIndicator(color = Color.White)
                !playback.isPlaying -> Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.35f), MaterialTheme.shapes.extraLarge)
                        .padding(8.dp),
                )
            }
        }
    }
}
