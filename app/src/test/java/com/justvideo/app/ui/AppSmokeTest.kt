package com.justvideo.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justvideo.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// =====================================================================
// AppSmokeTest.kt
// Opens the REAL app in a simulated Android (Robolectric) and taps
// through it, the way you would on your phone. It catches crashes
// before you have to install anything.
// =====================================================================

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    // The button (not the "Tap New project to start" hint text).
    private val newProjectButton = hasText("New project", substring = true) and hasClickAction()

    // STEP 1: Home shows its main parts.
    @Test
    fun homeShowsMainActions() {
        compose.onNodeWithText("Auto Highlights").assertIsDisplayed()
        compose.onNode(newProjectButton).assertIsDisplayed()
    }

    // STEP 2: New project → editor opens with its tools. Back → the project
    // is listed on Home.
    @Test
    fun createProjectOpensEditorAndIsListed() {
        compose.onNode(newProjectButton).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Media")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Audio") and hasClickAction()).assertIsDisplayed() // the tool, not the track label
        compose.onNodeWithText("Add a video", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Timeline").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("0 clips", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    // STEP 3: The Diagnostics log opens from the menu.
    @Test
    fun diagnosticsOpens() {
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Diagnostics").performClick()
        compose.onNodeWithText("Copy").assertIsDisplayed()
    }

    // STEP 4: The Create tab lists the categories.
    @Test
    fun createTabShowsCategories() {
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("What do you want to make?").assertIsDisplayed()
        compose.onNodeWithText("Edit a video").assertIsDisplayed()
    }

    // STEP 5: Auto Highlights opens, with the video picker button and its settings.
    @Test
    fun autoHighlightsOpens() {
        compose.onNodeWithText("Auto Highlights").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Choose a long video")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Highlight settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Preset")).fetchSemanticsNodes().isNotEmpty() }
    }
}
