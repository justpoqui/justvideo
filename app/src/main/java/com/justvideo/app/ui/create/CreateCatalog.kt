package com.justvideo.app.ui.create

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.ui.graphics.vector.ImageVector

// =====================================================================
// CreateCatalog.kt
// The tiles on the Create tab, one per kind of video. The categories come
// from the "awesome-claude-video-skills" list, reworked as OFFLINE,
// on-device features (no cloud services, no fees).
//
// To add or change a category, edit this list only. The tab draws
// itself from it.
// =====================================================================

/** What tapping a tile does. */
enum class CreateAction {
    NEW_PROJECT,      // opens a blank project in the editor (works today)
    AUTO_HIGHLIGHTS,  // Phase 2 headline feature
    COMING_SOON,      // planned, so we explain what it will do
}

data class CreateCategory(
    val id: String,
    val title: String,
    /** One line: what you'll be able to make. */
    val description: String,
    val icon: ImageVector,
    val action: CreateAction,
    /** Shown as a badge, e.g. "Ready" or "Phase 3". */
    val status: String,
)

object CreateCatalog {

    // STEP 1: The categories, roughly in the order they'll become usable.
    val categories = listOf(
        CreateCategory(
            id = "edit", title = "Edit a video",
            description = "Trim, split, reorder and add music on a timeline",
            icon = Icons.Rounded.ContentCut, action = CreateAction.NEW_PROJECT, status = "Ready",
        ),
        CreateCategory(
            id = "shorts", title = "Shorts & social",
            description = "Auto Highlights: long video → ranked 10–15 s vertical clips",
            icon = Icons.Rounded.AutoAwesome, action = CreateAction.AUTO_HIGHLIGHTS, status = "Phase 2",
        ),
        CreateCategory(
            id = "explainer", title = "Explainers",
            description = "Talking video with auto-captions, word-by-word highlight",
            icon = Icons.Rounded.Subtitles, action = CreateAction.COMING_SOON, status = "Phase 3",
        ),
        CreateCategory(
            id = "stories", title = "Stories & slideshows",
            description = "Photos with slow pan/zoom (Ken Burns) and transitions",
            icon = Icons.Rounded.AutoStories, action = CreateAction.COMING_SOON, status = "Phase 3",
        ),
        CreateCategory(
            id = "motion", title = "Motion graphics",
            description = "Animated titles, lower thirds and sticker presets",
            icon = Icons.Rounded.Animation, action = CreateAction.COMING_SOON, status = "Phase 3",
        ),
        CreateCategory(
            id = "music", title = "Music videos",
            description = "Cuts that snap to the beat of your song",
            icon = Icons.Rounded.GraphicEq, action = CreateAction.COMING_SOON, status = "Phase 4",
        ),
        CreateCategory(
            id = "promo", title = "Promo & demos",
            description = "Ready-made templates: intro card, highlights, end screen",
            icon = Icons.Rounded.Campaign, action = CreateAction.COMING_SOON, status = "Phase 4",
        ),
        CreateCategory(
            id = "tutorial", title = "Tutorials",
            description = "Step titles and zoom-ins for how-to videos",
            icon = Icons.Rounded.School, action = CreateAction.COMING_SOON, status = "Phase 4",
        ),
    )
}
