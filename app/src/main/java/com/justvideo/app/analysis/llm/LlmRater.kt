package com.justvideo.app.analysis.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.justvideo.app.core.AppLog
import java.io.Closeable
import java.io.File

// =====================================================================
// LlmRater.kt
// OPTIONAL, off by default. A small on-device language model (e.g. Gemma
// in MediaPipe ".task" format, which you pick in Settings) reads each
// candidate clip's transcript and rates how engaging it is (1–10). That
// rating is blended into the score.
// Only the top candidates are rated, never the whole video, so it adds
// seconds, not minutes. Needs a fairly strong phone (yours is).
// =====================================================================

class LlmRater private constructor(private val llm: LlmInference) : Closeable {

    // STEP 1: Rate one transcript snippet. Returns 1..10, or null if the
    // model's answer had no number in it.
    fun rate(snippet: String): Int? {
        if (snippet.isBlank()) return null
        val prompt = """
            You rate short-form video clips (TikTok/Reels/Shorts).
            Transcript of a 10–15 second clip:
            "${snippet.take(600)}"
            How engaging/viral is this moment on a scale of 1 to 10? Answer with ONLY the number.
        """.trimIndent()
        val answer = try {
            llm.generateResponse(prompt)
        } catch (e: Exception) {
            AppLog.w(TAG, "LLM failed: ${e.message}"); return null
        }
        return Regex("\\b(10|[1-9])\\b").find(answer)?.value?.toInt()
    }

    override fun close() = llm.close()

    companion object {
        private const val TAG = "LlmRater"

        /** Where the picked model file is kept (copied from your storage once). */
        fun modelFile(context: Context) = File(context.filesDir, "llm/model.task")

        // STEP 2: Load the model (several seconds, ~1 GB of memory for a 1B
        // model). Returns null if the file is missing or unsupported.
        fun load(context: Context, path: String): LlmRater? = try {
            if (!File(path).exists()) null
            else LlmRater(
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(512)
                        .build(),
                )
            )
        } catch (e: Throwable) {
            AppLog.e(TAG, "Couldn't load LLM", e)
            null
        }
    }
}
