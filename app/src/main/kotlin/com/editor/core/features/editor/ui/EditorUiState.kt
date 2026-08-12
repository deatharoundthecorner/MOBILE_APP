package com.editor.core.features.editor.ui

import com.editor.core.features.editor.domain.TextFile

/**
 * Sealed class representing all possible UI states for the editor.
 * Implements the state machine pattern for reactive UI updates.
 * Allows exhaustive when expressions for compile-time safety.
 */
sealed class EditorUiState {

    /**
     * Initial idle state with no active operations.
     */
    object Idle : EditorUiState()

    /**
     * Loading state indicating file read or save operation in progress.
     */
    object Loading : EditorUiState()

    /**
     * Success state with loaded file and optional status message.
     *
     * @property file Loaded TextFile domain object
     * @property message Optional user-facing success message
     */
    data class Success(
        val file: TextFile,
        val message: String? = null
    ) : EditorUiState()

    /**
     * Error state with exception details.
     * Contains full throwable for logging and user notification.
     *
     * @property throwable Exception that occurred
     */
    data class Error(
        val throwable: Throwable
    ) : EditorUiState()
}
