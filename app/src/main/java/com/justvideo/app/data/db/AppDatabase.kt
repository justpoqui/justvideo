package com.justvideo.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// =====================================================================
// AppDatabase.kt
// The app's single Room database file (justvideo.db, in private storage).
// Version 2 adds the Auto Highlights results table.
// =====================================================================

@Database(
    entities = [ProjectEntity::class, HighlightSessionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun highlightDao(): HighlightSessionDao

    companion object {
        // STEP 1: Upgrade v1 → v2 without losing your projects: just add the
        // new table.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `highlight_sessions` (`key` TEXT NOT NULL, `uri` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`highlights_json` TEXT NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        // STEP 2: Create the database. AppContainer calls this exactly once.
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "justvideo.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
