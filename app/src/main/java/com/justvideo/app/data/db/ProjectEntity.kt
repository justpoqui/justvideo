package com.justvideo.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// =====================================================================
// ProjectEntity.kt
// One row in the "projects" database table.
// =====================================================================

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
    // STEP 1: The whole Timeline, stored as JSON text.
    // Saving it as one value keeps saving simple, and it makes undo/redo
    // easy later (each edit is just a saved copy of the timeline).
    @ColumnInfo(name = "timeline_json") val timelineJson: String,
    // STEP 2: Copies of two timeline numbers, so the Home list can show them
    // without reading the JSON of every project.
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "clip_count") val clipCount: Int,
)
