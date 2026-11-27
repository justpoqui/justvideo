package com.justvideo.app.core.update

// =====================================================================
// UpdateModels.kt
// The data the updater works with, and every state it can be in.
// =====================================================================

// STEP 1: A newer build found on the GitHub Releases page.
data class UpdateInfo(
    /** Build number (Android "versionCode"). Bigger = newer. */
    val versionCode: Long,
    /** Human-readable name, e.g. "0.1.2". */
    val versionName: String,
    /** "What's new" text from the release. */
    val notes: String,
    /** Direct download link of the APK file. */
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** SHA-256 checksum from GitHub (null if GitHub didn't provide one). */
    val sha256: String?,
    /** The release page, for opening in the browser as a fallback. */
    val pageUrl: String,
)

// STEP 2: What the updater is doing right now. The Home screen draws
// itself from this.
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** progress = 0..1, or null if the size is unknown. */
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    /** Downloaded. Waiting for Android's install screen / your confirmation. */
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Android won't let the app install yet; you need to allow it in Settings. */
    data class NeedsInstallPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}
