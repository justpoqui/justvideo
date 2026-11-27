package com.justvideo.app.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.justvideo.app.JustVideoApp
import com.justvideo.app.core.AppLog

// =====================================================================
// InstallResultReceiver.kt
// Android calls this with the outcome of an update install.
// =====================================================================

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val manager = (context.applicationContext as JustVideoApp).container.updateManager
        AppLog.i("Updater", "Install status=$status msg=$message")

        when (status) {
            // STEP 1: Android needs you to confirm. Show its "Update?" screen.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    manager.onInstallFailed("Android didn't show the install screen.")
                }
            }

            // STEP 2: Success. Android is restarting the app as the new
            // version, so there's nothing else to do.
            PackageInstaller.STATUS_SUCCESS -> Unit

            // STEP 3: The update is signed with a different key than the
            // installed app (e.g. the build machine was wiped). Android
            // refuses to replace it, so you must uninstall once.
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> manager.onInstallFailed(
                "This update is signed with a different key. Uninstall JustVideo, then install the " +
                    "new version from the release page (your test projects will be removed)."
            )

            PackageInstaller.STATUS_FAILURE_ABORTED -> manager.onInstallFailed("Update cancelled.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> manager.onInstallFailed("Not enough free storage to install the update.")
            else -> manager.onInstallFailed("Install failed: ${message ?: "unknown error"} (code $status)")
        }
    }
}
