package com.justvideo.app.core.update

import android.content.Context
import com.justvideo.app.BuildConfig
import com.justvideo.app.core.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

// =====================================================================
// UpdateManager.kt
// Runs the whole update flow: check GitHub → download → install.
// It lives as long as the app (not just one screen), so a download keeps
// going if you open a project while it runs.
// =====================================================================

class UpdateManager(
    context: Context,
    private val client: GitHubReleaseClient,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // STEP 1: Automatic check when the app opens. To stay polite to
    // GitHub's limits it runs at most once every 3 hours, and it stays
    // silent if nothing new is found or there's no internet.
    fun checkAutomatically() {
        scope.launch {
            val last = withContext(Dispatchers.IO) { prefs.getLong(KEY_LAST_CHECK, 0) }
            if (clock() - last < AUTO_INTERVAL_MS) return@launch
            withContext(Dispatchers.IO) { downloader.cleanUp() } // remove old update files
            check(manual = false)
        }
    }

    // STEP 2: "Check for updates" from the menu: always runs and always
    // tells you the result.
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        try {
            val latest = client.fetchLatest()
            withContext(Dispatchers.IO) { prefs.edit().putLong(KEY_LAST_CHECK, clock()).apply() }
            val dismissed = withContext(Dispatchers.IO) { prefs.getLong(KEY_DISMISSED, 0) }
            _state.value = when {
                latest == null || latest.versionCode <= currentVersionCode ->
                    if (manual) UpdateState.UpToDate else UpdateState.Idle
                // On an automatic check, don't nag about a build you already
                // tapped "Later" on. A manual check always shows it.
                !manual && latest.versionCode <= dismissed -> UpdateState.Idle
                else -> UpdateState.Available(latest)
            }
            AppLog.i(TAG, "Check: installed=$currentVersionCode latest=${latest?.versionCode}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Update check failed: ${e.message}")
            _state.value = if (manual) UpdateState.Failed("Couldn't check for updates: ${e.message}", null) else UpdateState.Idle
        }
    }

    // STEP 3: You tapped "Update". Check the install permission, download, install.
    fun startUpdate(info: UpdateInfo) {
        if (job?.isActive == true) return
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsInstallPermission(info)
            return
        }
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                val apk = downloader.download(info) { p -> _state.value = UpdateState.Downloading(info, p) }
                _state.value = UpdateState.Installing(info)
                installer.install(apk)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Update failed", e)
                _state.value = UpdateState.Failed(e.message ?: "Update failed", info)
            }
        }
    }

    // STEP 4: Called whenever the app comes back to the screen. If you just
    // came back from Settings with the permission switched on, continue
    // automatically.
    fun onAppResumed() {
        val s = _state.value
        if (s is UpdateState.NeedsInstallPermission && installer.canInstall()) startUpdate(s.info)
    }

    fun installPermissionIntent() = installer.installPermissionSettingsIntent()

    fun cancelDownload() {
        job?.cancel()
    }

    // STEP 5: "Later": hide this build until a newer one appears.
    fun dismiss() {
        val s = _state.value
        val code = when (s) {
            is UpdateState.Available -> s.info.versionCode
            is UpdateState.Failed -> s.info?.versionCode
            is UpdateState.NeedsInstallPermission -> s.info.versionCode
            else -> null
        }
        if (code != null) scope.launch(Dispatchers.IO) { prefs.edit().putLong(KEY_DISMISSED, code).apply() }
        if (job?.isActive != true) _state.value = UpdateState.Idle
    }

    // Called by InstallResultReceiver when Android refuses the install.
    fun onInstallFailed(message: String) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = UpdateState.Failed(message, info)
    }

    private companion object {
        const val TAG = "Updater"
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_DISMISSED = "dismissed_version_code"
        const val AUTO_INTERVAL_MS = 3 * 60 * 60 * 1000L
    }
}
