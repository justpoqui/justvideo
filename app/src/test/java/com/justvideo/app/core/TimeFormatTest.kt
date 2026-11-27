package com.justvideo.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

// =====================================================================
// TimeFormatTest.kt
// Checks that times are shown the way the UI expects.
// =====================================================================

class TimeFormatTest {

    @Test
    fun shortDurations() {
        assertEquals("0:00", TimeFormat.duration(0))
        assertEquals("0:07", TimeFormat.duration(7_200))
        assertEquals("12:34", TimeFormat.duration(754_000))
    }

    @Test
    fun hourLongDurations() {
        assertEquals("1:02:03", TimeFormat.duration(3_723_000))
    }

    @Test
    fun preciseTime() {
        assertEquals("00:12.3", TimeFormat.precise(12_345))
        assertEquals("01:05.0", TimeFormat.precise(65_000))
    }

    @Test
    fun negativeIsClampedToZero() {
        assertEquals("0:00", TimeFormat.duration(-50))
    }
}
