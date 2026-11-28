package com.justvideo.app.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.justvideo.app.ui.theme.Teal

// =====================================================================
// CreateTab.kt
// The "Create" tab on Home: a grid of tiles, one per kind of video
// (see CreateCatalog.kt). Ready ones open straight into the editor; the
// rest say when they're coming.
// =====================================================================

@Composable
fun CreateTab(onCategory: (CreateCategory) -> Unit, modifier: Modifier = Modifier) {
    // STEP 1: Two columns of tiles, big enough to hit one-handed.
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                "What do you want to make?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(CreateCatalog.categories, key = { it.id }) { c ->
            CategoryTile(c, onClick = { onCategory(c) })
        }
    }
}

@Composable
private fun CategoryTile(c: CreateCategory, onClick: () -> Unit) {
    val ready = c.status == "Ready"
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // STEP 2: Icon + status badge on top.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                c.icon, contentDescription = null,
                tint = if (ready) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                c.status,
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (ready) Teal else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        // STEP 3: Title and description. Planned tiles are slightly faded.
        Column(Modifier.alpha(if (ready || c.action == CreateAction.AUTO_HIGHLIGHTS) 1f else 0.8f)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
