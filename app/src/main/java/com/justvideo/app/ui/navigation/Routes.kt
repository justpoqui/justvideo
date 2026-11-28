package com.justvideo.app.ui.navigation

import kotlinx.serialization.Serializable

// =====================================================================
// Routes.kt
// Every screen in the app, as a type. Navigation checks them when the
// app is compiled, so a typo in a screen name can't crash it.
// =====================================================================

@Serializable
data object HomeRoute

@Serializable
data class EditorRoute(val projectId: String)

@Serializable
data object HighlightsRoute

@Serializable
data object HighlightSettingsRoute
