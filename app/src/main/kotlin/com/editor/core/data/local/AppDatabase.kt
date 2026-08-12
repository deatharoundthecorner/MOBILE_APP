package com.editor.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.editor.core.data.local.entity.FileEntity
import com.editor.core.data.local.entity.FileVersionEntity

/**
 * Room database configuration and singleton instance manager.
 * Provides thread-safe access to the application database with lazy initialization.
 * Implements the singleton pattern with companion object for centralized instance management.
 */
@Database(
    entities = [FileEntity::class, FileVersionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun fileDao(): FileDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Gets or creates the singleton database instance.
         * Uses double-checked locking pattern for thread-safe lazy initialization.
         *
         * @param context Application context for database file creation
         * @return Singleton AppDatabase instance
         */
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: createDatabase(context).also { instance = it }
            }
        }

        /**
         * Creates a new Room database instance.
         * Configures database with appropriate settings for production use.
         *
         * @param context Application context
         * @return Newly created AppDatabase instance
         */
        private fun createDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration()
                .build()
        }

        private const val DATABASE_NAME = "editor_database.db"
    }
}
