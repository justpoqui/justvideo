package com.justvideo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.data.model.ProjectSummary
import com.justvideo.app.ui.theme.PanelHigh

// =====================================================================
// ProjectCard.kt
// One row in the Home drafts list: tap to open, ⋮ to rename/duplicate/delete.
// =====================================================================

@Composable
fun ProjectCard(
    project: ProjectSummary,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // STEP 1: Thumbnail: a frame of the first clip (an icon until it loads).
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                androidx.compose.foundation.Image(thumbnail, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(56.dp))
            } else {
                Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))

        // STEP 2: Name, length, clip count and last-edited time.
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${TimeFormat.duration(project.durationMs)} · ${project.clipCount} clips",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                TimeFormat.dateTime(project.updatedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // STEP 3: Overflow menu (48dp touch target from IconButton).
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Project options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { menuOpen = false; onDuplicate() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
