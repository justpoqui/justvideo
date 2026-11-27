package com.justvideo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// =====================================================================
// Theme.kt
// Material 3 theme. The app is always dark (like CapCut): video looks
// best on a dark background, whatever the phone's own setting is.
// =====================================================================

// STEP 1: Map our colors onto Material's named color slots.
private val JustVideoColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201D),
    secondary = Pink,
    onSecondary = Color.White,
    tertiary = Violet,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    outline = Outline,
    error = Danger,
)

// STEP 2: Text styles. Default Material sizes, slightly bolder titles.
private val base = Typography()
private val JustVideoType = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp),
)

@Composable
fun JustVideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JustVideoColors,
        typography = JustVideoType,
        content = content,
    )
}
