package com.justvideo.app.ui.create

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.GraphicEq
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
    NEW_PROJECT,      // a blank project in the editor
    AUTO_HIGHLIGHTS,  // long video → best clips
    EXPLAINER,        // pick videos → captions are generated automatically
    SLIDESHOW,        // pick photos → Ken Burns slideshow
    MOTION,           // blank project + animated title ready to edit
    MUSIC_VIDEO,      // pick videos, then a song → cuts on the beat
    PROMO,            // pick videos → title card + end card
    TUTORIAL,         // pick videos → "Step 1, 2, 3" titles
}

data class CreateCategory(
    val id: String,
    val title: String,
    /** One line: what you'll be able to make. */
    val description: String,
    val icon: ImageVector,
    val action: CreateAction,
    /** Shown as a badge. */
    val status: String = "Ready",
)

object CreateCatalog {

    // STEP 1: The categories.
    val categories = listOf(
        CreateCategory("edit", "Edit a video", "Trim, split, reorder, text, music, effects", Icons.Rounded.ContentCut, CreateAction.NEW_PROJECT),
        CreateCategory("shorts", "Shorts & social", "Auto Highlights: long video → ranked 10–15 s vertical clips", Icons.Rounded.AutoAwesome, CreateAction.AUTO_HIGHLIGHTS),
        CreateCategory("explainer", "Explainers", "Pick talking clips; word-by-word captions are added for you", Icons.Rounded.Subtitles, CreateAction.EXPLAINER),
        CreateCategory("stories", "Stories & slideshows", "Photos with slow pan/zoom (Ken Burns) and crossfades", Icons.Rounded.AutoStories, CreateAction.SLIDESHOW),
        CreateCategory("motion", "Motion graphics", "Animated titles: pop, slide, drift. Starts with a title to edit", Icons.Rounded.Animation, CreateAction.MOTION),
        CreateCategory("music", "Music videos", "Pick clips, then a song: cuts land on the beat", Icons.Rounded.GraphicEq, CreateAction.MUSIC_VIDEO),
        CreateCategory("promo", "Promo & demos", "Your clips with an animated title card and end card", Icons.Rounded.Campaign, CreateAction.PROMO),
        CreateCategory("tutorial", "Tutorials", "\"Step 1, 2, 3…\" titles on each clip", Icons.Rounded.School, CreateAction.TUTORIAL),
    )
}
