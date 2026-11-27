package com.justvideo.app.ui.editor.toolbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// =====================================================================
// EditorToolbar.kt
// The row of big tool buttons at the bottom of the editor (CapCut-style).
// It scrolls sideways once more tools are added in later phases.
// =====================================================================

@Composable
fun EditorToolbar(
    canDelete: Boolean,
    onAddMedia: () -> Unit,
    onAddAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding() // stay above the gesture bar
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // STEP 1: Tools. A disabled tool is faded out until it does something.
            ToolButton(Icons.Rounded.AddPhotoAlternate, "Media", enabled = true, onClick = onAddMedia)
            ToolButton(Icons.Rounded.LibraryMusic, "Audio", enabled = true, onClick = onAddAudio)
            ToolButton(Icons.Rounded.ContentCut, "Split", enabled = false, onClick = {}) // step 6
            ToolButton(Icons.Rounded.Delete, "Delete", enabled = canDelete, onClick = onDelete)
        }
    }
}

// STEP 2: One tool: icon above label, at least 72×64dp so it's easy to hit
// one-handed.
@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
