package com.justvideo.app.core

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
// CrashReporter.kt
// Catches crashes and saves a report to a file.
// The next time you open the app it shows the report with a Copy button,
// so you can paste it to me. This replaces Logcat, which needs a PC.
// =====================================================================

object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    // STEP 1: Install once, at app start (JustVideoApp calls this).
    // We wrap Android's own crash handler: write our report first, then let
    // Android handle the crash as usual (the app closes).
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                reportFile(appContext).writeText(buildReport(appContext, thread, error))
            } catch (_: Throwable) {
                // Never let the crash reporter itself crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    // STEP 2: Read the saved report (null if the app didn't crash last time).
    fun pendingReport(context: Context): String? =
        reportFile(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        reportFile(context).delete()
    }

    // STEP 3: Device info + the error + the recent app log, all in one text.
    private fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
        } catch (_: Exception) {
            "unknown"
        }
        return buildString {
            appendLine("=== JustVideo crash report ===")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("App: $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(error.stackTraceToString())
            appendLine("=== Recent app log ===")
            appendLine(AppLog.snapshot())
        }
    }

    // App-private folder: no permissions needed, and deleted on uninstall.
    private fun reportFile(context: Context) = File(context.filesDir, FILE_NAME)
}
