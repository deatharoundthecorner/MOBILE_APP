package com.editor.core.data.repository

import android.content.Context
import android.net.Uri
import com.editor.core.data.local.FileDao
import com.editor.core.data.local.entity.FileEntity
import com.editor.core.data.local.entity.FileVersionEntity
import com.editor.core.features.editor.domain.FileRepository
import com.editor.core.features.editor.domain.FileVersion
import com.editor.core.features.editor.domain.TextFile
import com.editor.core.features.versioncontrol.DiffEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset

/**
 * Implementation of FileRepository using disk I/O, Room persistence, and Storage Access Framework.
 * Handles reading/writing files to disk, persisting metadata via FileDao,
 * and managing version control through DiffEngine.
 * All I/O operations execute on Dispatchers.IO to prevent main thread blocking.
 */
class DiskFileRepository(
    private val context: Context,
    private val fileDao: FileDao
) : FileRepository {

    override suspend fun readFile(path: String, encoding: String): TextFile = withContext(Dispatchers.IO) {
        require(path.isNotBlank()) { "File path cannot be blank" }

        val content: String
        val name: String
        val isReadOnly: Boolean
        val lastModified: Long

        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            name = getFileNameFromUri(uri) ?: "unnamed.txt"
            
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Failed to open input stream for $path")
            
            content = inputStream.use { stream ->
                InputStreamReader(stream, Charset.forName(encoding)).use { reader ->
                    reader.readText()
                }
            }
            
            isReadOnly = false
            lastModified = System.currentTimeMillis()
        } else {
            val file = File(path)
            require(file.exists()) { "File does not exist: $path" }
            require(file.isFile) { "Path is not a file: $path" }

            content = runCatching {
                file.readText(Charset.forName(encoding))
            }.getOrElse { exception ->
                throw IOException("Failed to read file at $path: ${exception.message}", exception)
            }
            name = file.name
            isReadOnly = !file.canWrite()
            lastModified = file.lastModified()
        }

        // Update metadata in database
        val fileEntity = FileEntity(
            absolutePath = path,
            fileName = name,
            lastModified = lastModified,
            isReadOnly = isReadOnly
        )
        fileDao.insertOrUpdateFile(fileEntity)

        return@withContext TextFile(
            name = name,
            path = path,
            content = content,
            isReadOnly = isReadOnly
        )
    }

    override suspend fun writeFile(file: TextFile, encoding: String): Unit = withContext(Dispatchers.IO) {
        require(!file.isReadOnly) { "Cannot write to read-only file: ${file.path}" }

        val lastModified: Long
        val name: String

        if (file.path.startsWith("content://")) {
            val uri = Uri.parse(file.path)
            name = getFileNameFromUri(uri) ?: file.name
            
            val outputStream = context.contentResolver.openOutputStream(uri, "rwt")
                ?: throw IOException("Failed to open output stream for ${file.path}")
            
            outputStream.use { stream ->
                OutputStreamWriter(stream, Charset.forName(encoding)).use { writer ->
                    writer.write(file.content)
                }
            }
            lastModified = System.currentTimeMillis()
        } else {
            val ioFile = File(file.path)

            // Create parent directories if needed
            ioFile.parentFile?.mkdirs()

            val writeResult = runCatching {
                ioFile.writeText(file.content, Charset.forName(encoding))
            }

            if (writeResult.isFailure) {
                throw IOException(
                    "Failed to write file at ${file.path}: ${writeResult.exceptionOrNull()?.message}"
                )
            }
            name = ioFile.name
            lastModified = ioFile.lastModified()
        }

        // Update metadata in database
        val fileEntity = FileEntity(
            absolutePath = file.path,
            fileName = name,
            lastModified = lastModified,
            isReadOnly = false
        )
        fileDao.insertOrUpdateFile(fileEntity)
    }

    override suspend fun saveVersionSnapshot(
        path: String,
        commitMessage: String,
        encoding: String,
        currentContent: String?
    ): Unit = withContext(Dispatchers.IO) {
        require(path.isNotBlank()) { "File path cannot be blank" }
        require(commitMessage.isNotBlank()) { "Commit message cannot be blank" }

        val finalContent = currentContent ?: if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Failed to read file for snapshot: $path")
            inputStream.use { stream ->
                InputStreamReader(stream, Charset.forName(encoding)).use { it.readText() }
            }
        } else {
            val file = File(path)
            require(file.exists()) { "File does not exist: $path" }
            require(file.isFile) { "Path is not a file: $path" }
            runCatching {
                file.readText(Charset.forName(encoding))
            }.getOrElse { exception ->
                throw IOException("Failed to read file: ${exception.message}", exception)
            }
        }

        // Get previous version history to compute diff
        val previousVersionListDesc = fileDao.getVersionHistorySync(path)
        val previousVersionListAsc = previousVersionListDesc.reversed()

        // Reconstruct the full content of the latest version
        val lastVersionFullText = if (previousVersionListAsc.isNotEmpty()) {
            reconstructVersionText(previousVersionListAsc, previousVersionListAsc.size - 1)
        } else {
            ""
        }

        // Compute incremental patch (delta) from the latest version's full text
        val patch = if (previousVersionListAsc.isNotEmpty()) {
            runCatching {
                DiffEngine.computeDiff(lastVersionFullText, finalContent)
            }.getOrElse { "" }
        } else {
            finalContent // First version stores full text
        }

        // Optimization: Skip creating a new database record if nothing has changed since the last snapshot
        if (previousVersionListAsc.isNotEmpty() && patch.isEmpty()) {
            // android.util.Log.d("DiskFileRepository", "Skipping snapshot for $path: No changes detected")
            return@withContext
        }

        // Ensure file metadata is persisted along with version history.
        val fileEntity = FileEntity(
            absolutePath = path,
            fileName = if (path.startsWith("content://")) {
                Uri.parse(path).lastPathSegment ?: "unnamed"
            } else {
                File(path).name
            },
            lastModified = System.currentTimeMillis(),
            isReadOnly = false
        )
        fileDao.insertOrUpdateFile(fileEntity)

        val versionEntity = FileVersionEntity(
            filePath = path,
            timestamp = System.currentTimeMillis(),
            patchContent = patch, // Store incremental patch (delta)
            commitMessage = commitMessage
        )

        fileDao.insertVersion(versionEntity)
    }

    override suspend fun getVersionContent(path: String, versionId: Long): String = withContext(Dispatchers.IO) {
        val entities = fileDao.getVersionHistory(path).firstOrNull() ?: emptyList()
        if (entities.isEmpty()) return@withContext ""

        // Reverse to get ASC chronological order (oldest first)
        val entitiesAsc = entities.reversed()
        val targetIdx = entitiesAsc.indexOfFirst { it.versionId == versionId }
        if (targetIdx == -1) return@withContext ""

        reconstructVersionText(entitiesAsc, targetIdx)
    }

    private fun reconstructVersionText(versionsAsc: List<FileVersionEntity>, targetIndex: Int): String {
        if (versionsAsc.isEmpty() || targetIndex !in versionsAsc.indices) return ""
        var text = versionsAsc[0].patchContent // Version 1 is full text
        for (i in 1..targetIndex) {
            text = DiffEngine.applyPatch(text, versionsAsc[i].patchContent)
        }
        return text
    }

    override fun getVersionHistory(path: String): Flow<List<FileVersion>> {
        require(path.isNotBlank()) { "File path cannot be blank" }

        return fileDao.getVersionHistory(path).map { entities ->
            entities.map { entity ->
                FileVersion(
                    versionId = entity.versionId,
                    filePath = entity.filePath,
                    timestamp = entity.timestamp,
                    patchContent = entity.patchContent,
                    commitMessage = entity.commitMessage
                )
            }
        }
    }

    override suspend fun getVersionHistorySync(path: String): List<FileVersion> = withContext(Dispatchers.IO) {
        require(path.isNotBlank()) { "File path cannot be blank" }
        
        fileDao.getVersionHistorySync(path).map { entity ->
            FileVersion(
                versionId = entity.versionId,
                filePath = entity.filePath,
                timestamp = entity.timestamp,
                patchContent = entity.patchContent,
                commitMessage = entity.commitMessage
            )
        }
    }

    override fun getRecentFiles(limit: Int): Flow<List<TextFile>> {
        return fileDao.getRecentFiles(limit).map { entities ->
            entities.map { entity ->
                TextFile(
                    name = entity.fileName,
                    path = entity.absolutePath,
                    content = "",
                    isReadOnly = entity.isReadOnly
                )
            }
        }
    }

    override suspend fun restoreVersion(path: String, versionId: Long, encoding: String): Unit = withContext(Dispatchers.IO) {
        val content = getVersionContent(path, versionId)
        if (content.isEmpty()) return@withContext

        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val outputStream = context.contentResolver.openOutputStream(uri, "rwt")
                ?: throw IOException("Failed to open output stream for restore: $path")
            outputStream.use { stream ->
                OutputStreamWriter(stream, Charset.forName(encoding)).use { writer ->
                    writer.write(content)
                }
            }
        } else {
            val ioFile = File(path)
            ioFile.parentFile?.mkdirs()
            runCatching {
                ioFile.writeText(content, Charset.forName(encoding))
            }.getOrElse { ex ->
                throw IOException("Failed to restore file at $path: ${ex.message}", ex)
            }
        }

        // Update metadata
        val fileEntity = FileEntity(
            absolutePath = path,
            fileName = File(path).name,
            lastModified = System.currentTimeMillis(),
            isReadOnly = false
        )
        fileDao.insertOrUpdateFile(fileEntity)
    }

    override suspend fun compareVersions(path: String, versionAId: Long, versionBId: Long): String = withContext(Dispatchers.IO) {
        val a = getVersionContent(path, versionAId)
        val b = getVersionContent(path, versionBId)
        return@withContext DiffEngine.computeDiff(a, b)
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex)
                    }
                }
            }
        }
        return uri.path?.let { File(it).name }
    }
}

/**
 * Exception for file I/O errors with detailed context.
 */
class IOException(message: String, cause: Throwable? = null) : Exception(message, cause)
