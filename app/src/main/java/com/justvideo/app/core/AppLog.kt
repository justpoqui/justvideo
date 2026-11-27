package com.justvideo.app.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

// =====================================================================
// AppLog.kt
// A small log that lives inside the app.
// You don't use a PC, so you can't read Logcat. Instead the app keeps its
// last 200 log lines in memory. They are attached to crash reports and
// shown under Home ⋮ → Diagnostics, where you can copy them for me.
// =====================================================================

object AppLog {

    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // STEP 1: Log functions. Each one also writes to the normal Android log.
    fun i(tag: String, msg: String) {
        Log.i(tag, msg); add("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg); add("W", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        add("E", tag, if (t == null) msg else "$msg\n${t.stackTraceToString().take(2_000)}")
    }

    // STEP 2: A copy of all stored lines, oldest first.
    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    // STEP 3: Store a line, dropping the oldest when full.
    // `synchronized`, because logs can come from several threads at once.
    private fun add(level: String, tag: String, msg: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $msg"
        synchronized(lines) {
            if (lines.size == MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
    }
}
