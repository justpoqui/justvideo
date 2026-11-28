package com.justvideo.app.analysis.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.justvideo.app.core.AppLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

// =====================================================================
// AudioStreamDecoder.kt
// Decodes a video's soundtrack with the phone's hardware/software audio
// decoder (MediaCodec) and hands it out as 16 kHz mono chunks.
// STREAMING: only a few KB are in memory at any time, so a 2-hour video
// works the same as a 10-second one.
// =====================================================================

class AudioStreamDecoder(
    private val context: Context,
    private val uri: Uri,
    /** Optional range of the file to decode (ms). Default: all of it. */
    private val fromMs: Long = 0,
    private val toMs: Long = Long.MAX_VALUE,
) {

    /**
     * STEP 1: Decode everything. [onChunk] gets 16 kHz mono samples (-1..1),
     * [onProgress] gets 0..1. Returns false if the video has no sound track.
     * Stops quickly if the coroutine is cancelled.
     */
    suspend fun decode(onChunk: suspend (FloatArray) -> Unit, onProgress: (Float) -> Unit): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val fileUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // Range: jump to the start; progress is measured over the range.
            if (fromMs > 0) extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = if (toMs == Long.MAX_VALUE) fileUs else minOf(fileUs.takeIf { it > 0 } ?: Long.MAX_VALUE, toMs * 1000)
            val startUs = fromMs * 1000
            val durationUs = (endUs - startUs).coerceAtLeast(0)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var resampler = Resampler(rate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var scratch = FloatArray(8192)

            // STEP 2: The decode loop: feed compressed packets in, take PCM out.
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || (toMs != Long.MAX_VALUE && extractor.sampleTime > toMs * 1000)) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The real output format (some decoders change rate/channels).
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(rate, channels)
                    }
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        out.position(info.offset); out.limit(info.offset + info.size)
                        // STEP 3: PCM bytes → floats (-1..1).
                        val count = if (floatPcm) info.size / 4 else info.size / 2
                        if (scratch.size < count) scratch = FloatArray(count)
                        if (floatPcm) {
                            out.asFloatBuffer().get(scratch, 0, count)
                        } else {
                            val sb = out.asShortBuffer()
                            for (i in 0 until count) scratch[i] = sb.get(i) / 32768f
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        // Skip audio from before the range start (the seek lands a bit early).
                        if (info.presentationTimeUs >= startUs || startUs == 0L) {
                            val mono = resampler.process(scratch, count)
                            if (mono.isNotEmpty()) onChunk(mono)
                        }
                        if (durationUs > 0) onProgress(((info.presentationTimeUs - startUs).toFloat() / durationUs).coerceIn(0f, 1f))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            onProgress(1f)
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.e(TAG, "Audio decode failed", e)
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "AudioDecode"
    }
}
