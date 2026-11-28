package com.justvideo.app.analysis.visual

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.justvideo.app.core.AppLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// =====================================================================
// FrameSampler.kt
// Pulls low-resolution frames out of a video, ~2 per second, as fast as
// possible:
// - Only KEYFRAMES are decoded (they don't depend on other frames), so we
//   skip >90% of the decoding work. That's what makes 2-hour videos
//   feasible.
// - Each frame is shrunk right away to a tiny greyscale image (for
//   motion/scene cuts) and a small one (for face detection), so a frame
//   never stays in memory at full size.
// =====================================================================

/** One sampled frame. [faceGray] is width×height greyscale for ML Kit. */
class SampledFrame(
    val timeMs: Long,
    val tiny: ByteArray,          // 64×36 greyscale (motion/scene cuts)
    val faceGray: ByteArray,
    val faceWidth: Int,
    val faceHeight: Int,
    val rotationDegrees: Int,
)

class FrameSampler(
    private val context: Context,
    private val uri: Uri,
    private val stepMs: Long = 500,
    /** Optional range (ms). Default: the whole video. */
    private val fromMs: Long = 0,
    private val toMs: Long = Long.MAX_VALUE,
) {

    companion object {
        const val TINY_W = 64
        const val TINY_H = 36
        private const val FACE_LONG_SIDE = 320
        private const val TAG = "FrameSampler"
    }

    /**
     * STEP 1: Walk through the video keyframe by keyframe. [onFrame] gets each
     * sample, [onProgress] 0..1. Returns false if there's no video track.
     */
    suspend fun sample(onFrame: suspend (SampledFrame) -> Unit, onProgress: (Float) -> Unit): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

            // Ask for plain YUV frames in memory (no screen surface needed).
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var lastKeyUs = -1L
            var targetUs = fromMs * 1000
            val endUs = if (toMs == Long.MAX_VALUE) durationUs else minOf(durationUs, toMs * 1000)
            // STEP 2: For each target time, jump to the keyframe at/after it.
            while (endUs == 0L || targetUs <= endUs) {
                currentCoroutineContext().ensureActive()
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                var t = extractor.sampleTime
                if (t < 0) break
                if (t <= lastKeyUs) {
                    // Same keyframe again: move on to the next one.
                    extractor.seekTo(lastKeyUs + 1, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    t = extractor.sampleTime
                    if (t <= lastKeyUs || t < 0) break
                }
                lastKeyUs = t
                decodeOne(codec, extractor, info)?.let { onFrame(it.copyAt(t / 1000, rotation)) }
                if (t > endUs && endUs > 0) break
                if (endUs > 0) onProgress(((t - fromMs * 1000).toFloat() / (endUs - fromMs * 1000).coerceAtLeast(1)).coerceIn(0f, 1f))
                targetUs = maxOf(t + 1, targetUs + stepMs * 1000)
            }
            onProgress(1f)
            return true
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    // STEP 3: Decode exactly one keyframe: reset the decoder, give it the
    // frame plus "end of stream" (so it outputs immediately), take the picture.
    private fun decodeOne(codec: MediaCodec, extractor: MediaExtractor, info: MediaCodec.BufferInfo): SampledFrame? {
        codec.flush()
        val inIndex = codec.dequeueInputBuffer(50_000)
        if (inIndex < 0) return null
        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
        if (size < 0) return null
        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
        val eosIndex = codec.dequeueInputBuffer(50_000)
        if (eosIndex >= 0) codec.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

        var result: SampledFrame? = null
        repeat(40) { // up to ~2 s
            val out = codec.dequeueOutputBuffer(info, 50_000)
            if (out >= 0) {
                if (result == null && info.size > 0) {
                    codec.getOutputImage(out)?.use { img -> result = shrink(img) }
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return result
            }
        }
        return result
    }

    // STEP 4: Full-size frame → two small greyscale images. Only the Y
    // (brightness) plane is read. 10-bit HDR frames store 2 bytes per pixel,
    // so we take the high byte.
    private fun shrink(img: Image): SampledFrame {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val tenBit = img.format == ImageFormat.YCBCR_P010 || pixStride == 2
        val w = img.width
        val h = img.height
        fun lumaAt(x: Int, y: Int): Int {
            val i = y * rowStride + x * pixStride + if (tenBit) 1 else 0
            return buf.get(i).toInt() and 0xFF
        }
        val tiny = boxShrink(w, h, TINY_W, TINY_H, ::lumaAt)
        val scale = FACE_LONG_SIDE.toFloat() / maxOf(w, h)
        val fw = ((w * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val fh = ((h * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val face = boxShrink(w, h, fw, fh, ::lumaAt)
        return SampledFrame(0, tiny, face, fw, fh, 0)
    }

    // Average each block of source pixels into one output pixel.
    private inline fun boxShrink(w: Int, h: Int, ow: Int, oh: Int, luma: (Int, Int) -> Int): ByteArray {
        val out = ByteArray(ow * oh)
        val sx = w.toFloat() / ow
        val sy = h.toFloat() / oh
        val stepX = maxOf(1, (sx / 3).toInt()) // sample a few pixels per block, not all
        val stepY = maxOf(1, (sy / 3).toInt())
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var sum = 0; var c = 0
            var y = (oy * sy).toInt()
            val yEnd = minOf(h, ((oy + 1) * sy).toInt().coerceAtLeast(y + 1))
            while (y < yEnd) {
                var x = (ox * sx).toInt()
                val xEnd = minOf(w, ((ox + 1) * sx).toInt().coerceAtLeast(x + 1))
                while (x < xEnd) { sum += luma(x, y); c++; x += stepX }
                y += stepY
            }
            out[oy * ow + ox] = (if (c == 0) 0 else sum / c).toByte()
        }
        return out
    }

    private fun SampledFrame.copyAt(timeMs: Long, rotation: Int) =
        SampledFrame(timeMs, tiny, faceGray, faceWidth, faceHeight, rotation).also {
            if (timeMs % 600_000 < stepMs) AppLog.i(TAG, "Sampled ${timeMs / 1000}s")
        }
}
