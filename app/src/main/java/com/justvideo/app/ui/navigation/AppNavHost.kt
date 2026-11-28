package com.justvideo.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.justvideo.app.ui.editor.EditorScreen
import com.justvideo.app.ui.highlights.HighlightSettingsScreen
import com.justvideo.app.ui.highlights.HighlightsScreen
import com.justvideo.app.ui.home.HomeScreen

// =====================================================================
// AppNavHost.kt
// Connects the screens: which one shows first, and how to get between them.
// =====================================================================

@Composable
fun AppNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = HomeRoute) {

        // STEP 1: Home (project list + Auto Highlights button).
        composable<HomeRoute> {
            HomeScreen(
                onOpenProject = { id -> nav.navigate(EditorRoute(id)) },
                onOpenHighlights = { nav.navigate(HighlightsRoute) },
            )
        }

        // STEP 1b: Auto Highlights and its settings.
        composable<HighlightsRoute> {
            HighlightsScreen(
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(HighlightSettingsRoute) },
                onOpenProject = { id -> nav.navigate(EditorRoute(id)) },
            )
        }
        composable<HighlightSettingsRoute> {
            HighlightSettingsScreen(onBack = { nav.popBackStack() })
        }

        // STEP 2: Editor for one project. The project id goes along with the route.
        composable<EditorRoute> {
            EditorScreen(onBack = { nav.popBackStack() })
        }
    }
}
