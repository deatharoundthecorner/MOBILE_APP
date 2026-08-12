package com.editor.core.features.editor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.editor.core.data.repository.DiskFileRepository
import com.editor.core.features.editor.domain.TextFile
import com.editor.core.features.versioncontrol.DiffEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * MVVM ViewModel managing editor UI state and business logic orchestration.
 * Exposes StateFlow for reactive UI binding and provides functional operations.
 * All coroutines are scoped to ViewModel lifecycle for automatic cleanup.
 */
class EditorViewModel(
    private val repository: DiskFileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Idle)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _encoding = MutableStateFlow("UTF-8")
    val encoding: StateFlow<String> = _encoding.asStateFlow()

    val recentFiles: StateFlow<List<TextFile>> = repository.getRecentFiles(10)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * Set the text encoding format.
     */
    fun setEncoding(newEncoding: String) {
        _encoding.value = newEncoding
    }

    /**
     * Creates a new blank document state.
     */
    fun createNewDocument() {
        _uiState.value = EditorUiState.Success(
            file = TextFile(
                name = "unnamed.txt",
                path = "unsaved://new_document",
                content = "",
                isReadOnly = false
            ),
            message = "New document created"
        )
    }

    /**
     * Loads a file from disk and updates UI state.
     * Emits Loading state during operation, then Success or Error.
     *
     * @param path Absolute file path or content URI to open
     */
    fun openFile(path: String) {
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading

            val result = runCatching {
                repository.readFile(path, _encoding.value)
            }

            _uiState.value = result.fold(
                onSuccess = { file ->
                    EditorUiState.Success(file = file, message = "File opened: ${file.name}")
                },
                onFailure = { exception ->
                    EditorUiState.Error(exception)
                }
            )
        }
    }

    /**
     * Saves file content to disk and commits version snapshot.
     * Updates UI state to reflect save operation result.
     *
     * @param name File name
     * @param path Absolute file path or content URI
     * @param content Text content to save
     */
    fun saveFile(name: String, path: String, content: String) {
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading

            val result = runCatching {
                // Create TextFile domain object
                val file = TextFile(
                    name = name,
                    path = path,
                    content = content,
                    isReadOnly = false
                )

                // Write to disk/SAF
                repository.writeFile(file, _encoding.value)

                file
            }

            _uiState.value = result.fold(
                onSuccess = { file ->
                    EditorUiState.Success(file = file, message = "File saved successfully")
                },
                onFailure = { exception ->
                    EditorUiState.Error(exception)
                }
            )
        }
    }

    /**
     * Rolls back file to a previous version snapshot.
     * Restores the file content to the target snapshot text without creating a new automatic version.
     *
     * @param path Absolute file path
     * @param targetSnapshotText Complete text of target version
     */
    fun rollBackToVersion(
        path: String,
        targetSnapshotText: String,
        commitMsg: String
    ) {
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading

            val result = runCatching {
                // Create rollback file
                val rollbackFile = TextFile(
                    name = java.io.File(path).name,
                    path = path,
                    content = targetSnapshotText,
                    isReadOnly = false
                )

                // Write to disk
                repository.writeFile(rollbackFile, _encoding.value)

                // Save rollback as a version snapshot so it's committed in the version history
                repository.saveVersionSnapshot(
                    path = path,
                    commitMessage = commitMsg,
                    encoding = _encoding.value,
                    currentContent = targetSnapshotText
                )

                rollbackFile
            }

            _uiState.value = result.fold(
                onSuccess = { file ->
                    EditorUiState.Success(file = file, message = "Rolled back to previous version")
                },
                onFailure = { exception ->
                    EditorUiState.Error(exception)
                }
            )
        }
    }

    /**
     * Creates an explicit named version snapshot for the current active file.
     * Reloads file metadata to sync snapshot history.
     *
     * @param commitMessage Human-readable description
     */
    fun saveVersion(commitMessage: String, currentContent: String) {
        val currentState = _uiState.value
        if (currentState is EditorUiState.Success) {
            val path = currentState.file.path
            if (path == "unsaved://new_document") return

            viewModelScope.launch {
                _uiState.value = EditorUiState.Loading
                val result = runCatching {
                    repository.saveVersionSnapshot(path, commitMessage, _encoding.value, currentContent)
                }
                _uiState.value = result.fold(
                    onSuccess = {
                        EditorUiState.Success(file = currentState.file, message = "Version saved successfully")
                    },
                    onFailure = { exception ->
                        EditorUiState.Error(exception)
                    }
                )
            }
        }
    }

    /**
     * Reconstructs the full content of a specific historical version.
     *
     * @param path Absolute file path
     * @param versionId Historical snapshot ID
     * @return Complete text content of that version
     */
    suspend fun getVersionContent(path: String, versionId: Long): String {
        return repository.getVersionContent(path, versionId)
    }

    /**
     * Exposes the version history flow for the active file.
     */
    fun getVersionHistory(path: String) = repository.getVersionHistory(path)

    /**
     * Retrieves version history immediately as a snapshot.
     */
    suspend fun getVersionHistorySync(path: String) = repository.getVersionHistorySync(path)

    /**
     * Clears the current UI state back to Idle.
     */
    fun clearError() {
        _uiState.value = EditorUiState.Idle
    }
}
