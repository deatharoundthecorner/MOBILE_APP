package com.editor.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room database entity representing a file record.
 * Uses absolutePath as primary key for efficient queries.
 * Tracks file metadata including modification timestamp and read-only status.
 */
@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey
    @ColumnInfo(name = "absolute_path")
    val absolutePath: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    @ColumnInfo(name = "is_read_only")
    val isReadOnly: Boolean
) {
    init {
        require(absolutePath.isNotBlank()) { "Absolute path cannot be blank" }
        require(fileName.isNotBlank()) { "File name cannot be blank" }
        require(lastModified >= 0) { "Last modified timestamp must be non-negative" }
    }
}
