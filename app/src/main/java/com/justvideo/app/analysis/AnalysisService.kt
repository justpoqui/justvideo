package com.justvideo.app.analysis

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.justvideo.app.JustVideoApp
import com.justvideo.app.analysis.llm.LlmRater
import com.justvideo.app.core.AppLog
import com.justvideo.app.core.TimeFormat
import com.justvideo.app.media.export.ExportNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
// AnalysisService.kt
// Runs HighlightPipeline as a foreground service: a notification shows
// progress + time left + Cancel, and Android keeps it alive if you
// switch apps or lock the phone. A partial wake lock keeps the CPU awake
// with the screen off.
// =====================================================================

class AnalysisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val container get() = (application as JustVideoApp).container
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val (uri, settings) = container.analysisManager.pending ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = container.analysisManager
        val store = container.analysisStore

        // STEP 1: Foreground + wake lock (max 3 h, released when done).
        ExportNotifier.ensureChannel(this)
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            ExportNotifier.progress(this, "Finding highlights…", "Starting", null, cancelIntent()), type,
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JustVideo:analysis").apply { acquire(3 * 60 * 60 * 1000L) }

        job = scope.launch {
            val name = store.displayName(uri)
            val started = System.currentTimeMillis()
            var lastNotify = 0L
            try {
                // STEP 2: Cached? Then skip the slow part entirely.
                val key = store.keyFor(uri)
                val cached = store.loadResult(key)?.takeIf { !settings.useSpeech || it.hadSpeech || !container.speechModels.isReady(settings.speechModel) }
                val result = cached ?: withContext(Dispatchers.Default) {
                    HighlightPipeline(this@AnalysisService, container.speechModels).analyze(uri, settings) { p, stage ->
                        // Time left = time so far × (what's left ÷ what's done).
                        val elapsed = System.currentTimeMillis() - started
                        val eta = if (p > 0.03f) (elapsed * (1 - p) / p).toLong() else null
                        manager.report(AnalysisState.Running(uri, name, p, stage, eta))
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 1_000) {
                            lastNotify = now
                            notify("${(p * 100).toInt()}% · $stage" + (eta?.let { " · ${TimeFormat.duration(it)} left" } ?: ""), p)
                        }
                    }.also { store.saveResult(key, it) }
                }
                if (cached != null) AppLog.i(TAG, "Using cached analysis for $name")

                // STEP 3: Score + cut clips (+ optional LLM re-rank).
                manager.report(AnalysisState.Running(uri, name, 1f, "Picking the best moments", 0))
                val clips = withContext(Dispatchers.Default) {
                    val llm = if (settings.useLlm) settings.llmModelPath?.let { LlmRater.load(this@AnalysisService, it) } else null
                    try {
                        HighlightPipeline(this@AnalysisService, container.speechModels).rank(result, settings, llm)
                    } finally {
                        llm?.close()
                    }
                }
                store.saveHighlights(key, uri, name, result.features.durationMs, clips)
                AppLog.i(TAG, "Found ${clips.size} highlights in ${(System.currentTimeMillis() - started) / 1000}s")
                manager.report(AnalysisState.Done(key, uri, name))
                ExportNotifier.done(this@AnalysisService, "Highlights ready", "${clips.size} clips found in $name", null)
            } catch (e: CancellationException) {
                manager.report(AnalysisState.Failed("Analysis cancelled"))
            } catch (e: Throwable) {
                AppLog.e(TAG, "Analysis failed", e)
                manager.report(AnalysisState.Failed("Analysis failed: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                stopWork()
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(text: String, p: Float) {
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID, ExportNotifier.progress(this, "Finding highlights…", text, p, cancelIntent()),
            )
        } catch (_: SecurityException) {
        }
    }

    private fun stopWork() {
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 10, Intent(this, AnalysisService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "com.justvideo.analysis.START"
        const val ACTION_CANCEL = "com.justvideo.analysis.CANCEL"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "Analysis"
    }
}
