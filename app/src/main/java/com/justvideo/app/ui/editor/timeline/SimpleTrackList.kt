package com.justvideo.app.ui.editor.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.ui.theme.AudioGreen
import com.justvideo.app.ui.theme.PanelHigh
import com.justvideo.app.ui.theme.Teal

// =====================================================================
// SimpleTrackList.kt
// TEMPORARY timeline: two rows of clip "chips" (video row + audio row),
// so you can check imports now. Step 5 replaces this file with the real
// zoomable timeline (time ruler, thumbnails, playhead).
// =====================================================================

@Composable
fun SimpleTrackList(
    timeline: Timeline,
    selectedClipId: String?,
    onClipTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // STEP 1: Main video track.
        TrackRow("Video", timeline.videoClips, Teal, selectedClipId, onClipTapped)
        // STEP 2: Audio track.
        TrackRow("Audio", timeline.audioClips, AudioGreen, selectedClipId, onClipTapped)
    }
}

@Composable
private fun TrackRow(
    label: String,
    clips: List<Clip>,
    color: Color,
    selectedClipId: String?,
    onClipTapped: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp).padding(start = 12.dp),
        )
        if (clips.isEmpty()) {
            Text("empty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            // STEP 3: One chip per clip. Chip width grows with clip length
            // (clamped so very long or very short clips stay usable).
            LazyRow(
                contentPadding = PaddingValues(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipChip(clip, color, selected = clip.id == selectedClipId, onClick = { onClipTapped(clip.id) })
                }
            }
        }
    }
}

@Composable
private fun ClipChip(clip: Clip, color: Color, selected: Boolean, onClick: () -> Unit) {
    val widthDp = (clip.durationMs / 1000f * 12f).coerceIn(72f, 240f).dp
    Box(
        Modifier
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(PanelHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (clip.kind) {
                        MediaKind.VIDEO -> Icons.Rounded.Videocam
                        MediaKind.IMAGE -> Icons.Rounded.Image
                        MediaKind.AUDIO -> Icons.Rounded.MusicNote
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.height(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(TimeFormat.duration(clip.durationMs), style = MaterialTheme.typography.labelSmall)
            }
            Text(clip.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
