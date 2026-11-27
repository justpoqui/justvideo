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
    }

    // STEP 3: Android is low on memory. Phase 1 has no caches yet; the
    // thumbnail cache (step 5) will empty itself here.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLog.w("App", "onTrimMemory level=$level")
    }
}
