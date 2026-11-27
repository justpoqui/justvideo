package com.justvideo.app.core

import java.text.DateFormat
import java.util.Date

// =====================================================================
// TimeFormat.kt
// Turns milliseconds into text for the UI.
// =====================================================================

object TimeFormat {

    // STEP 1: Clip/project lengths: "0:07", "12:34", "1:02:03".
    fun duration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) + 500) / 1000 // round to the nearest second
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // STEP 2: Playhead time with tenths of a second: "00:12.3" (used for editing).
    fun precise(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val m = safe / 60_000
        val s = (safe % 60_000) / 1000
        val tenth = (safe % 1000) / 100
        return "%02d:%02d.%d".format(m, s, tenth)
    }

    // STEP 3: "Last edited" dates on the Home screen, in your phone's format.
    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
