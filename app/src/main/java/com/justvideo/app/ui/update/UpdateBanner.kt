package com.justvideo.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justvideo.app.core.update.UpdateInfo
import com.justvideo.app.core.update.UpdateState

// =====================================================================
// UpdateBanner.kt
// The card at the top of Home that shows the updater's progress:
// update available → downloading → installing (or what went wrong).
// It shows nothing when there's no update.
// =====================================================================

@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: (UpdateInfo) -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
) {
    // STEP 1: Pick the content for the current state. States that don't
    // need a card (idle, checking, up to date) show nothing.
    when (state) {
        is UpdateState.Available -> Card(title(state.info)) {
            if (state.info.notes.isNotBlank()) {
                Text(state.info.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Buttons {
                TextButton(onClick = onLater) { Text("Later") }
                Button(onClick = { onUpdate(state.info) }) { Text("Update") }
            }
        }

        is UpdateState.Downloading -> Card("Downloading ${state.info.versionName}…") {
            // STEP 2: Progress bar. If the size is unknown, it animates instead.
            val p = state.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(p?.let { "${(it * 100).toInt()}%" } ?: "", style = MaterialTheme.typography.bodySmall)
            Buttons { TextButton(onClick = onCancel) { Text("Cancel") } }
        }

        is UpdateState.Installing -> Card("Installing ${state.info.versionName}…") {
            Text("Confirm on Android's install screen. The app restarts when it's done.", style = MaterialTheme.typography.bodySmall)
        }

        // STEP 3: First update only: Android asks you to allow JustVideo to
        // install apps. Turn the switch on, come back, and it continues.
        is UpdateState.NeedsInstallPermission -> Card("One-time permission needed") {
            Text(
                "To install updates, allow JustVideo under \"Install unknown apps\". Turn the switch on, then come back here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Buttons {
                TextButton(onClick = onLater) { Text("Later") }
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
        }

        is UpdateState.Failed -> if (state.info != null) Card("Update didn't finish") {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Buttons {
                TextButton(onClick = { onOpenReleasePage(state.info.pageUrl) }) { Text("Release page") }
                TextButton(onClick = onLater) { Text("Later") }
                Button(onClick = { onUpdate(state.info) }) { Text("Retry") }
            }
        }

        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> Unit
    }
}

private fun title(info: UpdateInfo) = "Update available: ${info.versionName} (build ${info.versionCode})"

// STEP 4: Shared card look: outlined in the accent color with an update icon.
@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
