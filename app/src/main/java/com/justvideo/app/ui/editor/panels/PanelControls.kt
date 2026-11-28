package com.justvideo.app.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// =====================================================================
// PanelControls.kt
// Small building blocks shared by the tool panels: a labelled slider
// (a whole drag counts as ONE undo step), chips, a switch row and colour
// swatches.
// =====================================================================

/**
 * STEP 1: Labelled slider. [onLiveChange] runs while dragging (preview
 * updates live). [onStart] runs once when a drag begins, so the editor can
 * remember the state for undo.
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    onStart: () -> Unit,
    onLiveChange: (Float) -> Unit,
    steps: Int = 0,
) {
    var dragging by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText(value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                if (!dragging) { dragging = true; onStart() }
                onLiveChange(it)
            },
            onValueChangeFinished = { dragging = false },
            valueRange = range,
            steps = steps,
        )
    }
}

// STEP 2: A row of choice chips.
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit, enabled: (T) -> Boolean = { true }) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o ->
            FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }, enabled = enabled(o))
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// STEP 3: Colour swatches (big round touch targets).
val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD60A, 0xFFFF3B7F, 0xFF2BD9C8, 0xFF7B5CFF, 0xFF3DDC84, 0xFFFF6B00, 0xFF1E90FF,
).map { it.toInt() }

@Composable
fun SwatchRow(selected: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(SWATCHES) { c ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
fun PanelTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(320.dp))
