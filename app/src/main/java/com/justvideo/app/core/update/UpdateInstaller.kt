package com.justvideo.app.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
// UpdateInstaller.kt
// Hands the downloaded APK to Android's installer (the PackageInstaller
// API). Android checks the update is signed with the same key as the
// installed app, then replaces the app and keeps your projects.
// =====================================================================

class UpdateInstaller(private val context: Context) {

    // STEP 1: Android asks you once to allow "Install unknown apps" for
    // JustVideo. This tells us whether that's already allowed.
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    // STEP 2: An intent that opens exactly that Settings switch.
    fun installPermissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // STEP 3: Copy the APK into an install session and commit it.
    // The result arrives in InstallResultReceiver. Usually that's "user
    // action needed", which shows Android's own "Update this app?" screen.
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12+: an app updating ITSELF may skip the confirmation
            // screen. If Android doesn't allow it, it just shows the screen.
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            // FLAG_MUTABLE is required: Android writes the result into this intent.
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }
}
