# =====================================================================
# CMakeLists.txt — builds the on-device speech-to-text library.
#
# whisper.cpp (MIT licensed, vendored in ./whisper.cpp, CPU backend only)
# + our small JNI bridge (whisper_jni.cpp) → libjvwhisper.so
# Kotlin loads it from speech/WhisperBridge.kt.
# =====================================================================
cmake_minimum_required(VERSION 3.22.1)
project(jvwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# STEP 1: whisper.cpp settings.
# - Static libraries, linked into our single .so (simpler, smaller).
# - No tests/examples/server.
# - GGML_NATIVE OFF: generic ARMv8 code that runs on EVERY 64-bit phone,
#   not just the build machine's CPU.
# - No OpenMP: ggml's own thread pool works fine on Android.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

# STEP 2: Our bridge library.
add_library(jvwhisper SHARED whisper_jni.cpp)
target_link_libraries(jvwhisper PRIVATE whisper log)
target_compile_options(jvwhisper PRIVATE -O3 -fvisibility=hidden)
# Strip unused code from the final .so.
target_link_options(jvwhisper PRIVATE -Wl,--gc-sections)
