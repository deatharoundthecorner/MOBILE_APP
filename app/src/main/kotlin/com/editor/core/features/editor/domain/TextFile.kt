package com.editor.core.features.editor.domain

/**
 * Domain model representing a text file with metadata.
 * Encapsulates file information and immutable state for domain logic.
 *
 * @property name The file name (e.g., "document.txt")
 * @property path The absolute file path in the filesystem
 * @property content The complete text content of the file
 * @property isReadOnly Boolean flag indicating if the file is write-protected
 */
data class TextFile(
    val name: String,
    val path: String,
    val content: String,
    val isReadOnly: Boolean
) {
    init {
        require(name.isNotBlank()) { "File name cannot be blank" }
        require(path.isNotBlank()) { "File path cannot be blank" }
    }
}
