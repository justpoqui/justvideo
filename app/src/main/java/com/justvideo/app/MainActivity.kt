package com.justvideo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.justvideo.app.core.CrashReporter
import com.justvideo.app.ui.common.ReportDialog
import com.justvideo.app.ui.navigation.AppNavHost
import com.justvideo.app.ui.theme.JustVideoTheme

// =====================================================================
// MainActivity.kt
// The single Android screen container. Everything inside is drawn by Compose.
// =====================================================================

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // STEP 1: Draw behind the status bar and navigation bar (modern Android
        // look). Compose's Scaffold adds the right padding.
        enableEdgeToEdge()

        // STEP 2: Did the app crash last time? Read the saved report (it's a
        // tiny file, so reading it here is fine).
        val crashReport = if (savedInstanceState == null) CrashReporter.pendingReport(this) else null

        setContent {
            JustVideoTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // STEP 3: The app's screens.
                    AppNavHost()

                    // STEP 4: Crash report popup, shown above everything else.
                    var report by remember { mutableStateOf(crashReport) }
                    report?.let { text ->
                        ReportDialog(
                            title = "JustVideo crashed last time",
                            intro = "Sorry! Tap Copy and paste this into our chat so I can fix it.",
                            report = text,
                            onDismiss = {
                                CrashReporter.clear(this@MainActivity)
                                report = null
                            },
                        )
                    }
                }
            }
        }
    }
}
