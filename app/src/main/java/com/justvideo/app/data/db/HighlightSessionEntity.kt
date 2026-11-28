package com.justvideo.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// =====================================================================
// HighlightSessionEntity.kt
// One analyzed video in the database: which video, when, and the clip
// list (with your Keep/Discard choices). The big per-second measurements
// live in a file next to it (see AnalysisStore), because database rows
// should stay small.
// =====================================================================

@Entity(tableName = "highlight_sessions")
data class HighlightSessionEntity(
    /** Identifies the video (link + size + length), so a re-opened video is recognized. */
    @PrimaryKey val key: String,
    val uri: String,
    val name: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    /** The clips as JSON (List<Highlight>). */
    @ColumnInfo(name = "highlights_json") val highlightsJson: String,
)

@Dao
interface HighlightSessionDao {
    @Query("SELECT * FROM highlight_sessions ORDER BY created_at DESC")
    fun observeAll(): Flow<List<HighlightSessionEntity>>

    @Query("SELECT * FROM highlight_sessions WHERE `key` = :key")
    suspend fun get(key: String): HighlightSessionEntity?

    @Upsert
    suspend fun upsert(e: HighlightSessionEntity)

    @Query("DELETE FROM highlight_sessions WHERE `key` = :key")
    suspend fun delete(key: String)
}
