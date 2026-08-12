package com.editor.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity representing a versioned snapshot of a file.
 * Uses auto-generated primary key and foreign key constraint to FileEntity with CASCADE delete.
 * Stores complete snapshot text for historical reconstruction.
 */
@Entity(
    tableName = "file_versions",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["absolute_path"],
            childColumns = ["file_path"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(name = "idx_file_path", value = ["file_path"]),
        Index(name = "idx_timestamp", value = ["timestamp"])
    ]
)
data class FileVersionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "version_id")
    val versionId: Long = 0,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "patch_content")
    val patchContent: String,

    @ColumnInfo(name = "commit_message")
    val commitMessage: String
) {
    init {
        require(filePath.isNotBlank()) { "File path cannot be blank" }
        require(timestamp >= 0) { "Timestamp must be non-negative" }
        require(commitMessage.isNotBlank()) { "Commit message cannot be blank" }
    }
}
