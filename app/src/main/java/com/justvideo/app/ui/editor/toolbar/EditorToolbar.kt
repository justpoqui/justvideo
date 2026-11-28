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
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Opacity
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
import com.justvideo.app.ui.editor.SelectionKind

// =====================================================================
// EditorToolbar.kt
// The row of big tool buttons at the bottom of the editor (CapCut-style).
// It changes with what's selected: nothing → "add" tools; a clip → clip
// tools; a text or sticker → its tools. Scrolls sideways.
// =====================================================================

/** Every tool the toolbar can show. The screen decides what each one does. */
enum class Tool(val label: String, val icon: ImageVector) {
    MEDIA("Media", Icons.Rounded.AddPhotoAlternate),
    AUDIO("Audio", Icons.Rounded.LibraryMusic),
    TEXT("Text", Icons.Rounded.TextFields),
    CAPTIONS("Captions", Icons.Rounded.Subtitles),
    STICKER("Sticker", Icons.Rounded.EmojiEmotions),
    OVERLAY("Overlay", Icons.Rounded.PictureInPicture),
    SPLIT("Split", Icons.Rounded.ContentCut),
    SPEED("Speed", Icons.Rounded.Speed),
    VOLUME("Volume", Icons.Rounded.VolumeUp),
    TRANSITION("Transition", Icons.Rounded.Gradient),
    CROP("Crop", Icons.Rounded.Crop),
    REFRAME("Reframe", Icons.Rounded.CenterFocusStrong),
    COLOR("Color", Icons.Rounded.Palette),
    SILENCE("Silences", Icons.Rounded.GraphicEq),
    KEYFRAME("Keyframe", Icons.Rounded.Timeline),
    EDIT_TEXT("Edit", Icons.Rounded.Edit),
    STYLE("Style", Icons.Rounded.Tune),
    OPACITY("Opacity", Icons.Rounded.Opacity),
    DUPLICATE("Duplicate", Icons.Rounded.ContentCopy),
    DELETE("Delete", Icons.Rounded.Delete),
}

// STEP 1: Which tools appear for each kind of selection.
fun toolsFor(kind: SelectionKind): List<Tool> = when (kind) {
    SelectionKind.NONE -> listOf(Tool.MEDIA, Tool.AUDIO, Tool.TEXT, Tool.CAPTIONS, Tool.STICKER, Tool.OVERLAY, Tool.SPLIT)
    SelectionKind.VIDEO_CLIP -> listOf(
        Tool.SPLIT, Tool.SPEED, Tool.VOLUME, Tool.TRANSITION, Tool.CROP, Tool.REFRAME, Tool.COLOR,
        Tool.CAPTIONS, Tool.SILENCE, Tool.KEYFRAME, Tool.DUPLICATE, Tool.DELETE,
    )
    SelectionKind.AUDIO_CLIP -> listOf(Tool.SPLIT, Tool.SPEED, Tool.VOLUME, Tool.SILENCE, Tool.DUPLICATE, Tool.DELETE)
    SelectionKind.TEXT -> listOf(Tool.EDIT_TEXT, Tool.STYLE, Tool.KEYFRAME, Tool.DELETE)
    SelectionKind.OVERLAY -> listOf(Tool.OPACITY, Tool.KEYFRAME, Tool.DELETE)
}

@Composable
fun EditorToolbar(
    selection: SelectionKind,
    canSplit: Boolean,
    onTool: (Tool) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding() // stay above the gesture bar
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            for (tool in toolsFor(selection)) {
                ToolButton(tool.icon, tool.label, enabled = tool != Tool.SPLIT || canSplit, onClick = { onTool(tool) })
            }
        }
    }
}

// STEP 2: One tool: icon above label, at least 72×64dp so it's easy to hit
// one-handed. A disabled tool is faded out.
@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
