package com.justvideo.app.ui.editor.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justvideo.app.data.model.CaptionStyle
import com.justvideo.app.data.model.TextFont
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.media.captions.CaptionPager
import com.justvideo.app.media.captions.CaptionWord

// =====================================================================
// LayerPanels.kt
// Panels for things on top of the video: text, stickers, captions and
// overlay opacity.
// =====================================================================

// STEP 1: Add/edit text: words, font, colour, size, background box.
@Composable
fun TextEditorDialog(initial: TextItem, isNew: Boolean, onDone: (TextItem) -> Unit, onDismiss: () -> Unit) {
    var item by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add text" else "Edit text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = item.text, onValueChange = { item = item.copy(text = it.take(200)) }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                ChipRow(TextFont.entries, item.font, { it.label }, { item = item.copy(font = it) })
                SwatchRow(item.color) { item = item.copy(color = it) }
                SliderRow("Size", item.size, 0.03f..0.2f, { "${(it * 100).toInt()}" }, {}, { item = item.copy(size = it) })
                SwitchRow("Background box", item.background) { item = item.copy(background = it) }
                SliderRow("Shows for", item.durationMs.toFloat(), 500f..15_000f, { "%.1f s".format(it / 1000) }, {}, { item = item.copy(durationMs = (it / 100).toLong() * 100) })
            }
        },
        confirmButton = { TextButton(onClick = { onDone(item) }, enabled = item.text.isNotBlank()) { Text(if (isNew) "Add" else "Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// STEP 2: Stickers: tap an emoji to place it at the playhead.
private val EMOJIS = listOf(
    "🔥", "😂", "😱", "🤯", "😍", "👀", "💯", "🎉", "👏", "🙌", "😎", "🤔", "💀", "😭", "❤️", "⭐",
    "⚡", "✅", "❌", "👉", "👆", "🎮", "🏆", "⚽", "🏀", "🎵", "📢", "💥", "🚀", "🤑", "🥶", "🫡",
)

@Composable
fun StickerPicker(onPick: (String) -> Unit) {
    Column {
        PanelTitle("Stickers")
        LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.heightIn(max = 320.dp)) {
            items(EMOJIS) { e ->
                Text(e, fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.clickable { onPick(e) }.padding(8.dp))
            }
        }
    }
}

// STEP 3: Captions: generate (speech → words), show/hide, style, edit words.
@Composable
fun CaptionsPanel(
    hasCaptions: Boolean,
    show: Boolean,
    style: CaptionStyle,
    modelReady: Boolean,
    selectedOnly: Boolean,
    onGenerate: () -> Unit,
    onShow: (Boolean) -> Unit,
    onStyle: (CaptionStyle) -> Unit,
    onEditWords: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle("Captions")
        Text(
            if (modelReady) "Auto-captions are made on your phone from the speech." +
                if (selectedOnly) " Only the selected clip will be captioned." else ""
            else "Captions need the speech model. Download it in Auto Highlights → Settings (one time).",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onGenerate, enabled = modelReady, modifier = Modifier.fillMaxWidth()) {
            Text(if (hasCaptions) "Re-generate captions" else "Generate captions")
        }
        if (hasCaptions) {
            SwitchRow("Show captions", show, onShow)
            Text("Highlight colour", style = MaterialTheme.typography.bodyMedium)
            SwatchRow(style.accent) { onStyle(style.copy(accent = it)) }
            SliderRow("Position", style.positionY, -0.9f..0.9f, { if (it < -0.3f) "Bottom" else if (it > 0.3f) "Top" else "Middle" }, {}, { onStyle(style.copy(positionY = it)) })
            OutlinedButton(onClick = onEditWords, modifier = Modifier.fillMaxWidth()) { Text("Edit words") }
        }
    }
}

/**
 * STEP 4: Fix caption mistakes. Each line is one caption "page". If you
 * type a different number of words, the page's time is shared evenly
 * between the new words.
 */
@Composable
fun CaptionWordsDialog(words: List<CaptionWord>, onSave: (List<CaptionWord>) -> Unit, onDismiss: () -> Unit) {
    val pages = remember(words) { CaptionPager.pages(words) }
    val texts = remember(words) { mutableStateListOf(*pages.map { p -> p.words.joinToString(" ") { it.text.trim() } }.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit captions") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(pages) { i, _ ->
                    OutlinedTextField(value = texts[i], onValueChange = { texts[i] = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val out = mutableListOf<CaptionWord>()
                pages.forEachIndexed { i, p ->
                    val newWords = texts[i].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (newWords.size == p.words.size) {
                        out += p.words.mapIndexed { j, w -> w.copy(text = newWords[j]) }
                    } else if (newWords.isNotEmpty()) {
                        val span = (p.endMs - p.startMs).coerceAtLeast(newWords.size * 100L) / newWords.size
                        newWords.forEachIndexed { j, t -> out += CaptionWord(t, p.startMs + j * span, p.startMs + (j + 1) * span) }
                    }
                }
                onSave(out)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// STEP 5: Overlay opacity.
@Composable
fun OpacityPanel(opacity: Float, onStart: () -> Unit, onChange: (Float) -> Unit) {
    Column {
        PanelTitle("Opacity")
        SliderRow("Opacity", opacity, 0.1f..1f, { "${(it * 100).toInt()}%" }, onStart, onChange)
        Text("Drag the item on the preview to move it; pinch to resize.", style = MaterialTheme.typography.bodySmall)
    }
}

// STEP 6: Progress for slow tools (captions, reframe, silences).
@Composable
fun BusyDialog(label: String, progress: Float?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress?.let { "${(it * 100).toInt()}%" } ?: "Working…")
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
