package com.editor.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.editor.core.data.local.entity.FileEntity
import com.editor.core.data.local.entity.FileVersionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object providing transactional queries for file and version management.
 * Implements the DAO pattern with Flow-based reactive queries.
 */
@Dao
interface FileDao {

    /**
     * Inserts or updates a file record atomically.
     * Uses REPLACE strategy to handle existing records gracefully.
     *
     * @param file FileEntity to persist
     * @return Row ID of inserted/updated record
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFileIgnore(file: FileEntity): Long

    @androidx.room.Update
    suspend fun updateFile(file: FileEntity): Int

    @Query("SELECT rowid FROM files WHERE absolute_path = :absolutePath LIMIT 1")
    suspend fun getRowIdByPath(absolutePath: String): Long?

    @Transaction
    suspend fun insertOrUpdateFile(file: FileEntity): Long {
        val id = insertFileIgnore(file)
        if (id == -1L) {
            updateFile(file)
            return getRowIdByPath(file.absolutePath) ?: -1L
        }
        return id
    }

    /**
     * Retrieves a file record by its absolute path.
     * Returns null if file does not exist in database.
     *
     * @param absolutePath File path to query
     * @return FileEntity if found, null otherwise
     */
    @Query("SELECT * FROM files WHERE absolute_path = :absolutePath LIMIT 1")
    suspend fun getFileByPath(absolutePath: String): FileEntity?

    /**
     * Inserts a new version snapshot into version history.
     * Automatically cascades deletes if parent file is removed.
     *
     * @param version FileVersionEntity to insert
     * @return Row ID of inserted record
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersion(version: FileVersionEntity): Long

    /**
     * Retrieves complete version history for a file.
     * Results are ordered by timestamp in descending order (newest first).
     * Emits reactive updates when history changes.
     *
     * @param filePath File path to query history for
     * @return Flow<List<FileVersionEntity>> ordered by timestamp DESC
     */
    @Query(
        """
        SELECT * FROM file_versions 
        WHERE file_path = :filePath 
        ORDER BY timestamp DESC
        """
    )
    fun getVersionHistory(filePath: String): Flow<List<FileVersionEntity>>

    /**
     * Retrieves complete version history for a file as a single list.
     * Used for internal repository operations that need immediate, one-time access.
     */
    @Query(
        """
        SELECT * FROM file_versions 
        WHERE file_path = :filePath 
        ORDER BY timestamp DESC
        """
    )
    suspend fun getVersionHistorySync(filePath: String): List<FileVersionEntity>

    /**
     * Deletes all version records for a given file.
     * Used for cleanup when file is removed.
     *
     * @param filePath File path to delete versions for
     * @return Number of records deleted
     */
    @Query("DELETE FROM file_versions WHERE file_path = :filePath")
    suspend fun deleteVersionsByPath(filePath: String): Int

    /**
     * Retrieves the most recently opened/saved files ordered by last modified timestamp.
     *
     * @param limit Maximum number of records to return
     * @return Flow emitting list of recent files
     */
    @Query("SELECT * FROM files ORDER BY last_modified DESC LIMIT :limit")
    fun getRecentFiles(limit: Int): Flow<List<FileEntity>>

    /**
     * Transactional operation to update file metadata and create a version snapshot.
     * Ensures atomic consistency between file record and version history.
     *
     * @param fileEntity Updated file metadata
     * @param versionEntity New version snapshot
     */
    @Transaction
    suspend fun updateFileAndCreateVersion(
        fileEntity: FileEntity,
        versionEntity: FileVersionEntity
    ) {
        insertOrUpdateFile(fileEntity)
        insertVersion(versionEntity)
    }
}
