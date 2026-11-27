package com.justvideo.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// =====================================================================
// ProjectDao.kt
// The database queries for projects. Room generates the code behind them.
// Every function is either `suspend` or returns a Flow, so none of them
// can block the main (UI) thread.
// =====================================================================

@Dao
interface ProjectDao {

    // STEP 1: Live list for the Home screen, newest edits first.
    // A Flow re-emits automatically whenever the table changes.
    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    // STEP 2: Load a single project.
    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity)

    // STEP 3: Insert-or-update (used by every save).
    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    // STEP 4: Every project's timeline. Used to check whether another
    // project still uses a file before we give up access to it.
    @Query("SELECT timeline_json FROM projects")
    suspend fun allTimelineJson(): List<String>
}
