package com.justvideo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.justvideo.app.ui.theme.Pink
import com.justvideo.app.ui.theme.Violet

// =====================================================================
// AutoHighlightsCard.kt
// The big "Auto Highlights" button at the top of Home.
// Opens the Auto Highlights screen.
// =====================================================================

@Composable
fun AutoHighlightsCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // STEP 1: Pink→violet gradient so it stands out as the headline feature.
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp) // big touch target
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Pink, Violet)))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(16.dp))
        // STEP 2: Title + subtitle.
        Column {
            Text("Auto Highlights", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Turn a long video into ready-to-post 10–15 s clips, automatically",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
