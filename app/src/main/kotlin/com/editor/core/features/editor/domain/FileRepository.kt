package com.editor.core.features.editor.domain

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface defining clean abstractions for file operations.
 * Implements the Repository pattern for separation of concerns and testability.
 * All operations are suspending functions optimized for background execution.
 */
interface FileRepository {

    /**
     * Reads a file from storage and returns its domain model representation.
     * Executes on IO dispatcher to avoid blocking the main thread.
     *
     * @param path Absolute file path to read
     * @param encoding The text encoding format (e.g. UTF-8, UTF-16, US-ASCII)
     * @return TextFile domain object containing file metadata and content
     * @throws IllegalArgumentException if path is invalid
     * @throws IOException if file cannot be read
     */
    suspend fun readFile(path: String, encoding: String = "UTF-8"): TextFile

    /**
     * Writes file content to storage and updates metadata.
     * Atomically persists the file and commits a version snapshot.
     *
     * @param file TextFile domain object to persist
     * @param encoding The text encoding format (e.g. UTF-8, UTF-16, US-ASCII)
     * @throws IllegalStateException if file is marked as read-only
     * @throws IOException if write operation fails
     */
    suspend fun writeFile(file: TextFile, encoding: String = "UTF-8")

    /**
     * Creates a versioned snapshot of the current file state.
     * Computes incremental diff and stores version metadata.
     *
     * @param path Absolute file path
     * @param commitMessage Human-readable version description
     * @param encoding The text encoding format (e.g. UTF-8, UTF-16)
     * @throws IllegalArgumentException if path is invalid or not found
     */
    suspend fun saveVersionSnapshot(
        path: String,
        commitMessage: String,
        encoding: String = "UTF-8",
        currentContent: String? = null
    )

    /**
     * Retrieves complete version history for a file in reverse chronological order.
     * Returns Flow for reactive consumption of historical snapshots.
     *
     * @param path Absolute file path
     * @return Flow emitting list of version snapshots ordered by timestamp DESC
     */
    fun getVersionHistory(path: String): Flow<List<FileVersion>>

    /**
     * Retrieves version history immediately as a snapshot.
     */
    suspend fun getVersionHistorySync(path: String): List<FileVersion>

    /**
     * Reconstructs the complete text content of a specific version.
     *
     * @param path Absolute file path
     * @param versionId The ID of the version to reconstruct
     * @return Complete text content of that version
     */
    suspend fun getVersionContent(path: String, versionId: Long): String

    /**
     * Retrieves list of recently opened/saved files.
     *
     * @param limit Max number of entries to return
     * @return Flow of text file metadata (content is empty)
     */
    fun getRecentFiles(limit: Int = 10): Flow<List<TextFile>>
}

/**
 * Represents a single versioned snapshot in the version control history.
 */
data class FileVersion(
    val versionId: Long,
    val filePath: String,
    val timestamp: Long,
    val patchContent: String,
    val commitMessage: String
)
