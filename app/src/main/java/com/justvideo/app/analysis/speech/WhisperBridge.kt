package com.justvideo.app.analysis.speech

import java.io.Closeable

// =====================================================================
// WhisperBridge.kt
// Kotlin's door into whisper.cpp (the C++ code in app/src/main/cpp).
// One instance = one loaded speech model. Always close() it when done:
// the model uses ~100–300 MB of native memory.
// =====================================================================

class WhisperBridge private constructor(private var handle: Long) : Closeable {

    // STEP 1: Transcribe a chunk of 16 kHz mono audio. Blocking (call it
    // from a background thread). Returns raw text for TranscriptParser.
    fun transcribe(pcm: FloatArray, language: String, threads: Int): String {
        check(handle != 0L) { "Whisper model is closed" }
        return nativeTranscribe(handle, pcm, language, threads)
    }

    /** Stop a running transcribe() as soon as possible (Cancel button). */
    fun abort() {
        if (handle != 0L) nativeAbort(handle)
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    companion object {
        // STEP 2: Load the native library once. If it's missing (e.g. a
        // phone with an unsupported CPU type), speech is skipped instead of
        // crashing.
        val available: Boolean by lazy {
            try {
                System.loadLibrary("jvwhisper"); true
            } catch (_: Throwable) {
                false
            }
        }

        /** Load a model file; null if it can't be loaded (damaged download, out of memory). */
        fun load(modelPath: String): WhisperBridge? {
            if (!available) return null
            val h = nativeInit(modelPath)
            return if (h == 0L) null else WhisperBridge(h)
        }

        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeTranscribe(handle: Long, pcm: FloatArray, lang: String, threads: Int): String
        @JvmStatic private external fun nativeAbort(handle: Long)
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
