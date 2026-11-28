package com.justvideo.app.ui.editor.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.ClipTransform
import com.justvideo.app.data.model.ColorAdjust
import com.justvideo.app.data.model.FilterType
import com.justvideo.app.data.model.Keyframe
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Transition
import com.justvideo.app.data.model.TransitionType

// =====================================================================
// ClipPanels.kt
// The panels that edit the SELECTED CLIP: speed, volume & fades,
// transition, crop/zoom/pan, colour and keyframes. Each panel shows the
// clip's current values and reports changes through [onChange]
// (live = true while dragging) and [onStart] (a drag began, for undo).
// =====================================================================

// STEP 1: Speed 0.25×–4× with quick presets.
@Composable
fun SpeedPanel(clip: Clip, onStart: () -> Unit, onChange: (Boolean, (Clip) -> Clip) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle("Speed")
        if (clip.kind == MediaKind.IMAGE) {
            Text("Photos don't have a speed. Drag the clip's edges to change how long it shows.")
            return@Column
        }
        ChipRow(listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f), clip.speed, { "${it}×".replace(".0×", "×") }, { s -> onChange(false) { it.copy(speed = s) } })
        SliderRow("Custom", clip.speed, 0.25f..4f, { "%.2f×".format(it) }, onStart, { v -> onChange(true) { it.copy(speed = (v * 20).toInt() / 20f) } })
        Text("New length: ${TimeFormat.precise(clip.durationMs)} · voices keep their pitch", style = MaterialTheme.typography.bodySmall)
    }
}

// STEP 2: Volume 0–200 % and fade in/out.
@Composable
fun VolumePanel(clip: Clip, onStart: () -> Unit, onChange: (Boolean, (Clip) -> Clip) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("Volume")
        if (!clip.hasAudio && clip.kind != MediaKind.AUDIO) Text("This clip has no sound.", style = MaterialTheme.typography.bodySmall)
        SliderRow("Volume", clip.volume, 0f..2f, { "${(it * 100).toInt()}%" }, onStart, { v -> onChange(true) { it.copy(volume = (v * 20).toInt() / 20f) } })
        val maxFade = (clip.durationMs / 2).coerceAtMost(5_000).toFloat().coerceAtLeast(1f)
        SliderRow("Fade in", clip.fadeInMs.toFloat(), 0f..maxFade, { "%.1f s".format(it / 1000) }, onStart, { v -> onChange(true) { it.copy(fadeInMs = (v / 100).toLong() * 100) } })
        SliderRow("Fade out", clip.fadeOutMs.toFloat(), 0f..maxFade, { "%.1f s".format(it / 1000) }, onStart, { v -> onChange(true) { it.copy(fadeOutMs = (v / 100).toLong() * 100) } })
        TextButton(onClick = { onChange(false) { it.copy(volume = 0f) } }) { Text("Mute") }
    }
}

// STEP 3: Transition INTO this clip (from the clip before).
@Composable
fun TransitionPanel(clip: Clip, isFirst: Boolean, onStart: () -> Unit, onChange: (Boolean, (Clip) -> Clip) -> Unit, onApplyAll: (Transition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle("Transition in")
        if (isFirst) {
            Text("This is the first clip. Transitions go between two clips. Select the next clip.")
            return@Column
        }
        ChipRow(TransitionType.entries, clip.transitionIn.type, { it.label }, { t -> onChange(false) { it.copy(transitionIn = it.transitionIn.copy(type = t)) } })
        SliderRow(
            "Duration", clip.transitionIn.durationMs.toFloat(), 200f..2_000f, { "%.1f s".format(it / 1000) }, onStart,
            { v -> onChange(true) { it.copy(transitionIn = it.transitionIn.copy(durationMs = (v / 100).toLong() * 100)) } },
        )
        OutlinedButton(onClick = { onApplyAll(clip.transitionIn) }, modifier = Modifier.fillMaxWidth()) { Text("Apply to all clips") }
    }
}

// STEP 4: Crop / zoom / pan. Fit = black bars, Fill = crop to fill.
@Composable
fun CropPanel(clip: Clip, onStart: () -> Unit, onChange: (Boolean, (Clip) -> Clip) -> Unit) {
    val t = clip.transform
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("Crop & zoom")
        ChipRow(listOf(false, true), t.fill, { if (it) "Fill (crop)" else "Fit (bars)" }, { f -> onChange(false) { it.copy(transform = it.transform.copy(fill = f)) } })
        SliderRow("Zoom", t.scale, 1f..3f, { "%.2f×".format(it) }, onStart, { v -> onChange(true) { it.copy(transform = it.transform.copy(scale = v)) } })
        SliderRow("Move left/right", t.offsetX, -1f..1f, { "%.2f".format(it) }, onStart, { v -> onChange(true) { it.copy(transform = it.transform.copy(offsetX = v)) } })
        SliderRow("Move up/down", t.offsetY, -1f..1f, { "%.2f".format(it) }, onStart, { v -> onChange(true) { it.copy(transform = it.transform.copy(offsetY = v)) } })
        if (t.autoReframe) {
            Row {
                Text("Auto-reframe is on (follows faces).", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onChange(false) { it.copy(transform = it.transform.copy(autoReframe = false)) } }) { Text("Turn off") }
            }
        }
        TextButton(onClick = { onChange(false) { it.copy(transform = ClipTransform()) } }) { Text("Reset") }
    }
}

// STEP 5: Colour (Phase 4): adjustments, filters, LUT.
@Composable
fun ColorPanel(clip: Clip, onStart: () -> Unit, onChange: (Boolean, (Clip) -> Clip) -> Unit, onPickLut: () -> Unit, onApplyAll: (ColorAdjust) -> Unit) {
    val c = clip.color
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("Color")
        ChipRow(FilterType.entries, c.filter, { it.label }, { f ->
            if (f == FilterType.LUT && c.lutPath == null) onPickLut() else onChange(false) { it.copy(color = it.color.copy(filter = f)) }
        })
        if (c.filter != FilterType.NONE && c.filter != FilterType.MONO && c.filter != FilterType.LUT) {
            SliderRow("Filter strength", c.filterStrength, 0f..1f, { "${(it * 100).toInt()}%" }, onStart, { v -> onChange(true) { it.copy(color = it.color.copy(filterStrength = v)) } })
        }
        SliderRow("Brightness", c.brightness, -0.5f..0.5f, { "%+.2f".format(it) }, onStart, { v -> onChange(true) { it.copy(color = it.color.copy(brightness = v)) } })
        SliderRow("Contrast", c.contrast, -0.5f..0.5f, { "%+.2f".format(it) }, onStart, { v -> onChange(true) { it.copy(color = it.color.copy(contrast = v)) } })
        SliderRow("Saturation", c.saturation, 0f..2f, { "${(it * 100).toInt()}%" }, onStart, { v -> onChange(true) { it.copy(color = it.color.copy(saturation = v)) } })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickLut) { Text("Load LUT (.cube)") }
            OutlinedButton(onClick = { onApplyAll(c) }) { Text("Apply to all") }
        }
        TextButton(onClick = { onChange(false) { it.copy(color = ColorAdjust()) } }) { Text("Reset") }
    }
}

// STEP 6: Keyframes (Phase 4): animate position/zoom/opacity over time.
@Composable
fun KeyframePanel(keyframes: List<Keyframe>, onAdd: () -> Unit, onRemove: (Keyframe) -> Unit, onClear: () -> Unit, onSeek: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle("Keyframes")
        Text(
            "Move the playhead, place the item (drag it on the preview, or use Crop for clips), then tap Add. " +
                "It moves smoothly from one keyframe to the next.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add keyframe at playhead") }
        keyframes.forEach { k ->
            Row {
                TextButton(onClick = { onSeek(k.atMs) }, modifier = Modifier.weight(1f)) {
                    Text("◆ ${TimeFormat.precise(k.atMs)}  x %.2f  y %.2f  ×%.2f".format(k.x, k.y, k.scale))
                }
                TextButton(onClick = { onRemove(k) }) { Text("Remove") }
            }
        }
        if (keyframes.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear all") }
    }
}
