package com.justvideo.app.di

import android.content.Context
import com.justvideo.app.BuildConfig
import com.justvideo.app.analysis.AnalysisManager
import com.justvideo.app.analysis.AnalysisStore
import com.justvideo.app.analysis.speech.SpeechModelManager
import com.justvideo.app.core.update.GitHubReleaseClient
import com.justvideo.app.core.update.UpdateDownloader
import com.justvideo.app.core.update.UpdateInstaller
import com.justvideo.app.core.update.UpdateManager
import com.justvideo.app.data.db.AppDatabase
import com.justvideo.app.data.repo.MediaRepository
import com.justvideo.app.data.repo.ProjectRepository
import com.justvideo.app.media.export.ExportManager
import com.justvideo.app.media.thumbs.ThumbnailLoader

// =====================================================================
// AppContainer.kt
// Hand-written "dependency injection": builds each shared object exactly
// once and hands the same copy to every screen that needs it.
// (This replaces the Hilt library. See the plan for why.)
// =====================================================================

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    // STEP 1: The database. `by lazy` means it is only opened the first time
    // something uses it, which keeps app startup fast.
    private val database by lazy { AppDatabase.create(appContext) }

    // STEP 2: Repositories shared by every screen.
    val mediaRepository by lazy { MediaRepository(appContext) }
    val projectRepository by lazy { ProjectRepository(database.projectDao(), mediaRepository) }

    // STEP 3: Timeline thumbnails, shared by every screen (one memory-capped cache).
    val thumbnailLoader by lazy { ThumbnailLoader(appContext) }

    // STEP 4: Exports (one queue for the whole app; ExportService does the work).
    val exportManager by lazy { ExportManager(appContext) }

    // STEP 5: Auto Highlights: speech model files, cache/settings, and the
    // background analysis desk.
    val speechModels by lazy { SpeechModelManager(appContext) }
    val analysisStore by lazy { AnalysisStore(appContext, database.highlightDao()) }
    val analysisManager by lazy { AnalysisManager(appContext) }

    // Editor clip tools (captions, auto-reframe, silence removal).
    val clipTools by lazy { com.justvideo.app.editor.ClipTools(appContext, speechModels) }

    // STEP 6: The in-app updater (checks this app's GitHub Releases page).
    val updateManager by lazy {
        UpdateManager(
            context = appContext,
            client = GitHubReleaseClient(BuildConfig.UPDATE_REPO),
            downloader = UpdateDownloader(appContext.cacheDir),
            installer = UpdateInstaller(appContext),
        )
    }
}
