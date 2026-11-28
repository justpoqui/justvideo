package com.justvideo.app.media.effects

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

// =====================================================================
// GainProcessor.kt
// A clip's SOUND adjustments: volume (0–200%) and fade in/out. Media3
// passes the clip's audio through here, in small blocks, during preview
// and export. It counts how far into the clip each sample is, so fades
// land at the right moment, also after seeking.
// =====================================================================

@OptIn(UnstableApi::class)
class GainProcessor(
    private val volume: Float,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    /** The clip's length as it plays (speed included), for the fade-out. */
    private val clipDurationMs: Long,
) : BaseAudioProcessor() {

    private var frame = 0L // samples per channel processed since the clip start

    // STEP 1: We handle 16-bit PCM (what Media3 decodes to).
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun isActive(): Boolean = volume != 1f || fadeInMs > 0 || fadeOutMs > 0

    // STEP 2: Scale every sample by volume × fade envelope.
    override fun queueInput(input: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val bytes = input.remaining()
        val out = replaceOutputBuffer(bytes)
        val inShorts = input.order(ByteOrder.nativeOrder()).asShortBuffer()
        val count = bytes / 2
        val fadeInFrames = fadeInMs * rate / 1000
        val fadeOutFrames = fadeOutMs * rate / 1000
        val totalFrames = clipDurationMs * rate / 1000
        for (i in 0 until count) {
            val f = frame + i / channels
            var g = volume
            if (fadeInFrames > 0 && f < fadeInFrames) g *= f.toFloat() / fadeInFrames
            if (fadeOutFrames > 0 && f > totalFrames - fadeOutFrames) g *= ((totalFrames - f).toFloat() / fadeOutFrames).coerceIn(0f, 1f)
            val v = (inShorts.get(i) * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(v.toShort())
        }
        frame += count / channels
        input.position(input.limit())
        out.flip()
    }

    // STEP 3: After a seek, Media3 tells us where playback restarts.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        frame = if (inputAudioFormat.sampleRate > 0) streamMetadata.positionOffsetUs * inputAudioFormat.sampleRate / 1_000_000 else 0
    }

    override fun onReset() {
        frame = 0
    }
}
