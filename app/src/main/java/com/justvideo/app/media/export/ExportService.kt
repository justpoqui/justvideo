package com.justvideo.app.media.export

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.justvideo.app.JustVideoApp
import com.justvideo.app.core.AppLog
import com.justvideo.app.media.player.CompositionBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
// ExportService.kt
// Renders videos in the background with Media3 Transformer, using the
// phone's HARDWARE video encoder. It's a "foreground service": Android
// keeps it running (with a notification) even if you leave the app.
//
// Flow per job: Timeline → Composition → Transformer writes an MP4 into
// the app cache → copied into the gallery → cache file deleted.
// =====================================================================

@OptIn(UnstableApi::class)
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager get() = (application as JustVideoApp).container.exportManager
    private var transformer: Transformer? = null
    private var progressJob: Job? = null
    private var current: ExportJob? = null
    private var currentFile: File? = null
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelAll()
            else -> if (transformer == null) {
                // STEP 1: Go foreground right away (Android requires it within seconds).
                ExportNotifier.ensureChannel(this)
                val n = ExportNotifier.progress(this, "Exporting…", "Preparing", null, cancelPendingIntent())
                val type = when {
                    Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    else -> 0
                }
                ServiceCompat.startForeground(this, ExportNotifier.ONGOING_ID, n, type)
                cancelled = false
                runNext()
            }
        }
        return START_NOT_STICKY
    }

    // STEP 2: Take the next job from the queue and start rendering it.
    private fun runNext() {
        val job = manager.nextJob()
        if (job == null || cancelled) {
            finishAll()
            return
        }
        current = job
        val composition = CompositionBuilder.build(job.timeline, job.format, this)
        if (composition == null) {
            manager.failedCount++
            runNext()
            return
        }
        val out = File(cacheDir, "export_${job.id}.mp4").also { it.delete() }
        currentFile = out

        // Encoder: H.264 (plays everywhere) at ~12 Mbit/s for 1080p. Media3
        // picks the hardware encoder and falls back if a setting isn't supported.
        val encoder = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrateFor(job)).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(listener)
            .build()
        transformer = t
        AppLog.i(TAG, "Export start ${job.title} ${job.format} ${job.timeline.durationMs} ms")
        t.start(composition, out.absolutePath)
        watchProgress(job)
    }

    private fun bitrateFor(job: ExportJob): Int {
        val pixels = job.format.width * job.format.height
        return if (pixels >= 1920 * 1080) 12_000_000 else 8_000_000
    }

    // STEP 3: Poll Transformer's progress twice a second, and update the
    // notification and the app.
    private fun watchProgress(job: ExportJob) {
        progressJob?.cancel()
        progressJob = scope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                val t = transformer ?: break
                val p = if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null
                manager.report(ExportState.Running(job, p ?: 0f, manager.batchIndex, manager.batchTotal))
                val label = if (manager.batchTotal > 1) "Clip ${manager.batchIndex} of ${manager.batchTotal}" else job.title
                notify(ExportNotifier.progress(this@ExportService, "Exporting…", "$label · ${((p ?: 0f) * 100).toInt()}%", p, cancelPendingIntent()))
                delay(500)
            }
        }
    }

    // STEP 4: Transformer finished one video (or failed).
    private val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            progressJob?.cancel()
            val file = currentFile
            transformer = null
            scope.launch {
                try {
                    val uri = withContext(Dispatchers.IO) { GallerySaver.saveVideo(this@ExportService, file!!, current?.fileBase ?: "JustVideo") }
                    manager.finished += uri
                    AppLog.i(TAG, "Saved $uri (${exportResult.fileSizeBytes} bytes)")
                } catch (e: Exception) {
                    AppLog.e(TAG, "Saving to gallery failed", e)
                    manager.failedCount++
                } finally {
                    file?.delete()
                }
                runNext()
            }
        }

        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            progressJob?.cancel()
            AppLog.e(TAG, "Export failed: ${exportException.errorCodeName}", exportException)
            transformer = null
            currentFile?.delete()
            manager.failedCount++
            if (manager.batchTotal <= 1) {
                manager.clearQueue()
                manager.report(ExportState.Failed("Export failed: ${exportException.errorCodeName}"))
                stopWork()
            } else {
                runNext()
            }
        }
    }

    // STEP 5: Cancel button (notification or app).
    private fun cancelAll() {
        cancelled = true
        manager.clearQueue()
        transformer?.cancel()
        transformer = null
        progressJob?.cancel()
        currentFile?.delete()
        manager.report(ExportState.Failed("Export cancelled"))
        stopWork()
    }

    // STEP 6: Queue empty: report the result and stop the service.
    private fun finishAll() {
        val uris = manager.finished.toList()
        manager.report(ExportState.Done(uris, manager.failedCount))
        val text = when {
            uris.isEmpty() -> "Nothing was exported"
            uris.size == 1 -> "Saved to Gallery › Movies › JustVideo"
            else -> "${uris.size} videos saved to Gallery › Movies › JustVideo"
        }
        ExportNotifier.done(this, if (uris.isNotEmpty()) "Export finished" else "Export failed", text, uris.singleOrNull())
        stopWork()
    }

    private fun stopWork() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Android 15+: media-processing services have a daily time limit. If
    // it's reached, stop cleanly instead of being killed.
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(TAG, "Foreground service time limit reached")
        cancelAll()
    }

    override fun onDestroy() {
        transformer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(this).notify(ExportNotifier.ONGOING_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getService(
        this, 0, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "com.justvideo.export.START"
        const val ACTION_CANCEL = "com.justvideo.export.CANCEL"
        private const val TAG = "Export"
    }
}
