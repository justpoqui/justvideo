package com.justvideo.app.media.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.justvideo.app.MainActivity
import com.justvideo.app.R

// =====================================================================
// ExportNotifier.kt
// The notifications for background work: a progress bar with Cancel
// while it runs, and "Saved to gallery" when it's done. Also used by the
// Auto Highlights analysis (Phase 2).
// =====================================================================

object ExportNotifier {

    const val CHANNEL_ID = "work"
    const val ONGOING_ID = 1001
    private const val DONE_ID = 1002

    // STEP 1: Android 8+ needs a "channel" (the category you can mute in Settings).
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Exports & analysis", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of video exports and Auto Highlights"
                }
            )
        }
    }

    // STEP 2: The ongoing notification (progress bar + Cancel button).
    fun progress(context: Context, title: String, text: String, progress: Float?, cancelIntent: PendingIntent): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setContentIntent(openApp(context))
            .addAction(0, "Cancel", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    // STEP 3: Finished. Tapping it opens the video (or the app if there are several).
    fun done(context: Context, title: String, text: String, open: Uri?) {
        val tap = if (open != null) {
            PendingIntent.getActivity(
                context, 2,
                Intent(Intent.ACTION_VIEW).setDataAndType(open, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            openApp(context)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
        } catch (_: SecurityException) {
            // Notifications not allowed. The app shows the result anyway.
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
