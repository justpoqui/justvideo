package com.justvideo.app.data

import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.data.repo.TimelineJson
import org.junit.Assert.assertEquals
import org.junit.Test

// =====================================================================
// TimelineJsonTest.kt
// Checks that a saved project comes back identical, and that damaged
// data doesn't crash the app.
// =====================================================================

class TimelineJsonTest {

    private val clip = Clip(
        id = "1", sourceUri = "content://media/1", kind = MediaKind.VIDEO, displayName = "a.mp4",
        sourceDurationMs = 10_000, trimStartMs = 1_000, trimEndMs = 9_000,
        width = 1080, height = 1920, rotationDegrees = 90, hasAudio = true,
    )

    @Test
    fun roundTrip() {
        val tl = Timeline(videoClips = listOf(clip), audioClips = listOf(clip.copy(id = "2", kind = MediaKind.AUDIO, timelineOffsetMs = 500)))
        assertEquals(tl, TimelineJson.decode(TimelineJson.encode(tl)))
    }

    @Test
    fun corruptTextGivesEmptyTimeline() {
        assertEquals(Timeline(), TimelineJson.decode("{not json"))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = TimelineJson.encode(Timeline(videoClips = listOf(clip))).replaceFirst("{", "{\"futureField\":42,")
        assertEquals(1, TimelineJson.decode(json).videoClips.size)
    }
}
