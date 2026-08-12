package com.editor.core.features.editor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.editor.core.data.repository.DiskFileRepository

/**
 * Factory for creating EditorViewModel instances with dependency injection.
 * Implements the Factory pattern for clean separation of object creation.
 * Allows injection of DiskFileRepository dependency.
 */
class EditorViewModelFactory(
    private val repository: DiskFileRepository
) : ViewModelProvider.Factory {

    /**
     * Creates a new EditorViewModel instance with injected dependencies.
     *
     * @param modelClass ViewModel class to instantiate
     * @return ViewModel instance properly configured
     * @throws IllegalArgumentException if modelClass is not EditorViewModel
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
            return EditorViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
