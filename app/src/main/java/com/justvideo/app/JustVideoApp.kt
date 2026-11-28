package com.justvideo.app

import android.app.Application
import com.justvideo.app.core.AppLog
import com.justvideo.app.core.CrashReporter
import com.justvideo.app.di.AppContainer

// =====================================================================
// JustVideoApp.kt
// Runs once when the app process starts, before any screen.
// =====================================================================

class JustVideoApp : Application() {

    /** Shared objects (database, repositories). Screens get them from here. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // STEP 1: Start catching crashes as early as possible.
        CrashReporter.install(this)
        // STEP 2: Build the shared objects.
        container = AppContainer(this)
        AppLog.i("App", "JustVideo started")
        // STEP 2b: Delete half-finished export files left by a crash or a
        // killed app (they can be hundreds of MB).
        Thread {
            cacheDir.listFiles { f -> f.name.startsWith("export_") && f.name.endsWith(".mp4") }
                ?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000 }
                ?.forEach { it.delete() }
        }.start()
    }

    // STEP 3: Android is low on memory: empty the thumbnail cache (the
    // pictures reload as needed). Level 15+ means memory is getting tight or
    // the app went to the background.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLog.w("App", "onTrimMemory level=$level")
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_LOW) container.thumbnailLoader.clear()
    }
}
