package com.justvideo.app.ui.highlights

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justvideo.app.analysis.model.Highlight
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.media.thumbs.ThumbKey
import com.justvideo.app.media.thumbs.ThumbnailLoader
import com.justvideo.app.ui.theme.Danger
import com.justvideo.app.ui.theme.PanelHigh
import com.justvideo.app.ui.theme.Pink
import com.justvideo.app.ui.theme.Teal

// =====================================================================
// HighlightCard.kt
// One suggested clip: thumbnail, time range, length, score, why it was
// picked, what's said, plus Keep / Discard / Preview / Edit.
// =====================================================================

/** The thumbnail for a clip: a frame at its peak moment. */
fun thumbKey(uri: String, h: Highlight) = ThumbKey(uri, com.justvideo.app.data.model.MediaKind.VIDEO, (h.peakMs / 500) * 500)

@Composable
fun HighlightCard(
    rank: Int,
    h: Highlight,
    uri: String,
    thumbs: ThumbnailLoader,
    onKeep: (Boolean?) -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
) {
    val discarded = h.kept == false
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (h.kept == true) 2.dp else 0.dp, if (h.kept == true) Teal else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onPreview)
            .alpha(if (discarded) 0.45f else 1f)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // STEP 1: Thumbnail (9:16) with the rank and a play icon.
        Box(
            Modifier
                .width(72.dp)
                .size(72.dp, 120.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelHigh),
            contentAlignment = Alignment.Center,
        ) {
            thumbs[thumbKey(uri, h)]?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp, 120.dp))
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
            Text(
                "#$rank", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))

        // STEP 2: Details.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeFormat.duration(h.startMs)} – ${TimeFormat.duration(h.endMs)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.width(6.dp))
                Text("${h.durationMs / 1000}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // Score badge.
                Text(
                    "${h.score}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Pink).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text("Why: ${h.whyText}", style = MaterialTheme.typography.bodySmall, color = Teal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (h.snippet.isNotBlank()) {
                Text("“${h.snippet}”", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            // STEP 3: Actions (48dp touch targets).
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconToggleButton(checked = h.kept == true, onCheckedChange = { onKeep(if (it) true else null) }) {
                    Icon(Icons.Rounded.Check, "Keep")
                }
                FilledIconToggleButton(checked = discarded, onCheckedChange = { onKeep(if (it) false else null) }) {
                    Icon(Icons.Rounded.Close, "Discard", tint = if (discarded) Color.White else Danger)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Open in editor") }
            }
        }
    }
}
