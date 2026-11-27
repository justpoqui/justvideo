package com.justvideo.app.data.repo

import com.justvideo.app.data.model.Timeline
import kotlinx.serialization.json.Json

// =====================================================================
// TimelineJson.kt
// Converts a Timeline to and from the JSON text stored in the database.
// =====================================================================

object TimelineJson {

    // STEP 1: JSON settings.
    // - ignoreUnknownKeys: an older app version can still open a project
    //   saved by a newer one (it skips fields it doesn't know).
    // - encodeDefaults: always write every field, so the saved text is complete.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(timeline: Timeline): String = json.encodeToString(Timeline.serializer(), timeline)

    // STEP 2: Decode. Damaged text gives an empty timeline instead of a
    // crash, so one broken project can't stop the app from opening.
    fun decode(text: String): Timeline =
        runCatching { json.decodeFromString(Timeline.serializer(), text) }
            .getOrElse { Timeline() }
}
