package com.justvideo.app.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.justvideo.app.core.TimeFormat

// =====================================================================
// PreviewPane.kt
// The video preview at the top of the editor.
// For now it is a placeholder. Step 4 puts the real player here.
// =====================================================================

@Composable
fun PreviewPane(totalDurationMs: Long, isEmpty: Boolean, modifier: Modifier = Modifier) {
    // STEP 1: Black stage, like a video player.
    Box(
        modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // STEP 2: A hint until playback is built.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (isEmpty) "Add a video, photo or audio file with the tools below"
                else "Length ${TimeFormat.duration(totalDurationMs)}\nPreview playback comes in step 4",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
