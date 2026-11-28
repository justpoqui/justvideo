// =====================================================================
// whisper_jni.cpp
// The bridge between Kotlin (speech/WhisperBridge.kt) and whisper.cpp.
//
// Kotlin calls:
//   nativeInit(modelPath)            -> handle (0 = failed)
//   nativeTranscribe(handle, pcm, language, threads) -> text result
//   nativeAbort(handle)              -> stop a running transcription ASAP
//   nativeFree(handle)
//
// The result is plain text, one item per line, tab-separated:
//   S <t0 ms> <t1 ms> <segment text>      (a segment ≈ a sentence)
//   T <t0 ms> <t1 ms> <prob> <token text> (one word-piece, for word timing)
// Times are relative to the start of the PCM chunk passed in.
// =====================================================================

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include "whisper.h"

#define TAG "jvwhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// STEP 1: Everything one loaded model needs, behind a single handle.
struct JvWhisper {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

// Text must not contain our separators (tab/newline).
static std::string clean(const char *s) {
    std::string out = s ? s : "";
    for (char &c : out) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    return out;
}

// whisper.cpp asks this regularly; returning true stops the work.
static bool abort_cb(void *user) {
    return static_cast<JvWhisper *>(user)->abort.load();
}

extern "C" {

// STEP 2: Load a ggml model file (e.g. ggml-tiny.en-q5_1.bin).
JNIEXPORT jlong JNICALL
Java_com_justvideo_app_analysis_speech_WhisperBridge_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU only: works on every phone
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) {
        LOGE("Failed to load model");
        return 0;
    }
    auto *h = new JvWhisper();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

// STEP 3: Transcribe one chunk of 16 kHz mono float audio.
JNIEXPORT jstring JNICALL
Java_com_justvideo_app_analysis_speech_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray jpcm, jstring jlang, jint threads) {
    auto *h = reinterpret_cast<JvWhisper *>(handle);
    if (!h || !h->ctx) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(jpcm);
    jfloat *pcm = env->GetFloatArrayElements(jpcm, nullptr);
    const char *lang = env->GetStringUTFChars(jlang, nullptr);

    // Settings: fastest reliable decoding (greedy, no temperature retries),
    // word-level timestamps on, nothing printed.
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;
    p.translate = false;
    p.no_context = true;        // each chunk on its own: avoids repeated hallucinations
    p.single_segment = false;
    p.token_timestamps = true;  // per-token start/end times
    p.split_on_word = true;
    p.suppress_blank = true;
    p.temperature_inc = 0.0f;   // no slow re-decoding fallbacks
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = h;

    h->abort = false;
    int rc = whisper_full(h->ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);

    std::string out;
    if (rc == 0) {
        const whisper_token eot = whisper_token_eot(h->ctx);
        const int segments = whisper_full_n_segments(h->ctx);
        for (int s = 0; s < segments; ++s) {
            // Segment times are in 10 ms units.
            long t0 = whisper_full_get_segment_t0(h->ctx, s) * 10;
            long t1 = whisper_full_get_segment_t1(h->ctx, s) * 10;
            out += "S\t" + std::to_string(t0) + "\t" + std::to_string(t1) + "\t" +
                   clean(whisper_full_get_segment_text(h->ctx, s)) + "\n";
            const int tokens = whisper_full_n_tokens(h->ctx, s);
            for (int t = 0; t < tokens; ++t) {
                whisper_token_data d = whisper_full_get_token_data(h->ctx, s, t);
                if (d.id >= eot) continue; // skip special tokens ([BEG], timestamps, …)
                out += "T\t" + std::to_string(d.t0 * 10) + "\t" + std::to_string(d.t1 * 10) + "\t" +
                       std::to_string(d.p) + "\t" + clean(whisper_full_get_token_text(h->ctx, s, t)) + "\n";
            }
        }
    } else {
        LOGI("whisper_full returned %d (aborted or failed)", rc);
    }
    env->ReleaseStringUTFChars(jlang, lang);
    return env->NewStringUTF(out.c_str());
}

// STEP 4: Ask a running transcription to stop (used by Cancel).
JNIEXPORT void JNICALL
Java_com_justvideo_app_analysis_speech_WhisperBridge_nativeAbort(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<JvWhisper *>(handle);
    if (h) h->abort = true;
}

// STEP 5: Free the model's memory.
JNIEXPORT void JNICALL
Java_com_justvideo_app_analysis_speech_WhisperBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<JvWhisper *>(handle);
    if (!h) return;
    if (h->ctx) whisper_free(h->ctx);
    delete h;
}

} // extern "C"
