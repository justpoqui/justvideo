package com.justvideo.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// =====================================================================
// AppDatabase.kt
// The app's single Room database file (justvideo.db, in private storage).
// Phase 2 adds tables for highlight results to this same database.
// =====================================================================

@Database(
    entities = [ProjectEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    companion object {
        // STEP 1: Create the database. AppContainer calls this exactly once.
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "justvideo.db")
                .build()
    }
}
