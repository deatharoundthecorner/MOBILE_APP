package com.editor.core.features.editor.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.editor.core.R
import com.editor.core.data.local.AppDatabase
import com.editor.core.data.repository.DiskFileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Represent a snapshot of text and selection indices for undo/redo.
 */
private data class TextState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

/**
 * Manage undo and redo state stacks with capacity limitations.
 */
private class UndoRedoManager(private val maxCapacity: Int = 100) {
    private val undoStack = java.util.Stack<TextState>()
    private val redoStack = java.util.Stack<TextState>()

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun pushState(state: TextState) {
        val current = undoStack.lastOrNull()
        if (current == null || current.text != state.text) {
            undoStack.push(state)
            if (undoStack.size > maxCapacity) {
                undoStack.removeAt(0)
            }
            redoStack.clear()
        }
    }

    fun undo(currentState: TextState): TextState? {
        if (!canUndo()) return null
        redoStack.push(currentState)
        return undoStack.pop()
    }

    fun redo(currentState: TextState): TextState? {
        if (!canRedo()) return null
        undoStack.push(currentState)
        return redoStack.pop()
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

/**
 * Main editor activity implementing Material 3 design.
 * Uses ViewBinding for safe view access.
 * Manages file operations through EditorViewModel with lifecycle-aware coroutines.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var editText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var viewModel: EditorViewModel

    // Search and Replace Panel views
    private lateinit var searchPanel: android.view.View
    private lateinit var searchQuery: EditText
    private lateinit var replaceText: EditText
    private lateinit var cbCaseSensitive: android.widget.CheckBox
    private lateinit var cbWholeWord: android.widget.CheckBox
    private lateinit var cbRegex: android.widget.CheckBox

    // Version tracking
    private var currentVersionName: String? = null

    // Toolbar Undo/Redo/Save/Search views
    private lateinit var btnToolbarUndo: android.widget.ImageButton
    private lateinit var btnToolbarRedo: android.widget.ImageButton
    private lateinit var btnToolbarSave: android.widget.ImageButton
    private lateinit var btnToolbarSearch: android.widget.ImageButton

    // Cache recovery variables
    private var autoCacheJob: kotlinx.coroutines.Job? = null
    private val recoveryCacheFile by lazy { java.io.File(cacheDir, "recovery_cache.tmp") }

    // Read-only Mode variables
    private var isReadOnlyMode = false
    private var originalKeyListener: android.text.method.KeyListener? = null

    // Syntax Highlighting variables
    private enum class SyntaxMode { PLAIN_TEXT, KOTLIN, MARKDOWN }
    private var currentSyntaxMode = SyntaxMode.PLAIN_TEXT
    private var isApplyingSyntaxHighlight = false
    private var syntaxHighlightJob: kotlinx.coroutines.Job? = null

    private val kotlinKeywordRegex = Regex("\\b(fun|val|var|class|object|if|else|for|while|return|import|package|when|data|private|public|override)\\b")
    private val kotlinCommentRegex = Regex("//.*")
    private val kotlinStringRegex = Regex("\".*?\"")

    private val markdownHeadingRegex = Regex("(?m)^#+.*")
    private val markdownBoldRegex = Regex("\\*\\*.*?\\*\\*")
    private val markdownCodeRegex = Regex("`[^`\\n]+`")
    private val markdownListRegex = Regex("(?m)^[ \\t]*([-*])[ \\t]+.*")

    private var currentFilePath: String? = null

    // Undo/Redo tracking properties
    private val undoRedoManager = UndoRedoManager()
    private var isRestoringState = false

    // Search navigation state
    private var searchMatches = mutableListOf<Int>()
    private var currentMatchIndex = -1
    private var lastSearchQuery = ""

    // Wrap state
    private var isWordWrapEnabled = true

    // SAF launcher for opening files
    private val openDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            persistUriPermission(it, writable = false)
            currentFilePath = it.toString()
            currentVersionName = null
            viewModel.openFile(it.toString())
        }
    }

    // SAF launcher for Save As
    private val createDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            persistUriPermission(it, writable = true)
            currentFilePath = it.toString()
            currentVersionName = null
            val displayName = getFileNameFromUri(it) ?: "document.txt"
            val content = editText.text.toString()
            viewModel.saveFile(displayName, it.toString(), content)
        }
    }

    private val textWatcher = object : android.text.TextWatcher {
        private var lastText: String = ""
        private var lastSelectionStart: Int = 0
        private var lastSelectionEnd: Int = 0

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!isRestoringState && !isApplyingSyntaxHighlight) {
                lastText = s?.toString() ?: ""
                lastSelectionStart = editText.selectionStart
                lastSelectionEnd = editText.selectionEnd
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (isRestoringState || isApplyingSyntaxHighlight) return

            val textStr = s?.toString() ?: ""
            if (lastText != textStr) {
                undoRedoManager.pushState(TextState(
                    text = lastText,
                    selectionStart = lastSelectionStart,
                    selectionEnd = lastSelectionEnd
                ))
                lastText = textStr
                updateUndoRedoButtons()
                invalidateOptionsMenu()
                
                scheduleSyntaxHighlight()

                // Refresh search if panel is open
                if (searchPanel.visibility == android.view.View.VISIBLE && lastSearchQuery.isNotEmpty()) {
                    performSearch(lastSearchQuery, shouldHighlight = false)
                }
            }
        }

        override fun afterTextChanged(s: android.text.Editable?) {}

        fun forceRecordInitialState(text: String) {
            lastText = text
            lastSelectionStart = 0
            lastSelectionEnd = 0
            undoRedoManager.clear()
            updateUndoRedoButtons()
            invalidateOptionsMenu()
        }

        fun syncCurrentText(text: String) {
            lastText = text
            lastSelectionStart = editText.selectionStart
            lastSelectionEnd = editText.selectionEnd
            updateUndoRedoButtons()
            invalidateOptionsMenu()
        }
    }

    private fun persistUriPermission(uri: android.net.Uri, writable: Boolean) {
        // Try to persist READ permission
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Ignore if provider doesn't support persistable permission
        }

        // Try to persist WRITE permission separately
        if (writable) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Ignore
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        // Initialize views
        toolbar = findViewById(R.id.toolbar)
        editText = findViewById(R.id.editText)
        progressBar = findViewById(R.id.progressBar)
        originalKeyListener = editText.keyListener

        searchPanel = findViewById(R.id.searchPanel)
        searchQuery = findViewById(R.id.searchQuery)
        replaceText = findViewById(R.id.replaceText)
        cbCaseSensitive = findViewById(R.id.cbCaseSensitive)
        cbWholeWord = findViewById(R.id.cbWholeWord)
        cbRegex = findViewById(R.id.cbRegex)

        val btnFind = findViewById<android.view.View>(R.id.btnFind)
        val btnFindPrev = findViewById<android.view.View>(R.id.btnFindPrev)
        val btnFindNext = findViewById<android.view.View>(R.id.btnFindNext)
        val btnCloseSearch = findViewById<android.view.View>(R.id.btnCloseSearch)
        val btnReplace = findViewById<android.view.View>(R.id.btnReplace)
        val btnReplaceAll = findViewById<android.view.View>(R.id.btnReplaceAll)

        btnFind.setOnClickListener {
            performSearch(searchQuery.text.toString(), keepSearchFocus = false)
        }

        btnToolbarUndo = findViewById(R.id.btnToolbarUndo)
        btnToolbarRedo = findViewById(R.id.btnToolbarRedo)
        btnToolbarSave = findViewById(R.id.btnToolbarSave)
        btnToolbarSearch = findViewById(R.id.btnToolbarSearch)

        btnToolbarUndo.setOnClickListener { performUndo() }
        btnToolbarRedo.setOnClickListener { performRedo() }
        btnToolbarSave.setOnClickListener { saveCurrentFile() }
        btnToolbarSearch.setOnClickListener { toggleSearchPanel() }

        // Setup TextWatcher for Undo/Redo logic
        editText.addTextChangedListener(textWatcher)

        // Initialize button enabled/alpha states
        updateUndoRedoButtons()

        // Setup toolbar as action bar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Text Editor"

        // Initialize ViewModel with dependencies
        val database = AppDatabase.getInstance(applicationContext)
        val fileDao = database.fileDao()
        val repository = DiskFileRepository(applicationContext, fileDao)
        val factory = EditorViewModelFactory(repository)

        viewModel = ViewModelProvider(this, factory)[EditorViewModel::class.java]

        // Setup UI state collection
        setupUiStateCollection()

        // Bind Search / Replace panel actions
        btnCloseSearch.setOnClickListener {
            searchPanel.visibility = android.view.View.GONE
            editText.setSelection(editText.selectionStart)
        }

        val searchOptionsListener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            performSearch(searchQuery.text.toString())
        }
        cbCaseSensitive.setOnCheckedChangeListener(searchOptionsListener)
        cbWholeWord.setOnCheckedChangeListener(searchOptionsListener)
        cbRegex.setOnCheckedChangeListener(searchOptionsListener)

        searchQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnFindNext.setOnClickListener { navigateMatches(forward = true) }
        btnFindPrev.setOnClickListener { navigateMatches(forward = false) }

        btnReplace.setOnClickListener {
            if (isReadOnlyMode) {
                Toast.makeText(this, R.string.msg_readonly_blocked_save, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (searchMatches.isEmpty() || currentMatchIndex !in searchMatches.indices) {
                Toast.makeText(this, "No active match selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            recordUndoStateBeforeAction()

            val start = searchMatches[currentMatchIndex]
            val replacement = replaceText.text.toString()
            
            isRestoringState = true
            editText.text.replace(start, start + lastSearchQuery.length, replacement)
            editText.setSelection(start + replacement.length)
            isRestoringState = false

            textWatcher.syncCurrentText(editText.text.toString())
            performSearch(lastSearchQuery, keepSearchFocus = true)
            if (searchMatches.isNotEmpty()) {
                currentMatchIndex = currentMatchIndex.coerceIn(0, searchMatches.size - 1)
                highlightCurrentMatch(keepSearchFocus = true)
            }
            Toast.makeText(this, "Replaced", Toast.LENGTH_SHORT).show()
        }

        btnReplaceAll.setOnClickListener {
            if (isReadOnlyMode) {
                Toast.makeText(this, R.string.msg_readonly_blocked_save, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val query = searchQuery.text.toString()
            if (query.isEmpty()) {
                Toast.makeText(this, "Search query is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val text = editText.text.toString()
            val replacement = replaceText.text.toString()

            val newText = text.replace(query, replacement, ignoreCase = true)
            if (newText != text) {
                recordUndoStateBeforeAction()
                isRestoringState = true
                editText.setText(newText)
                isRestoringState = false

                textWatcher.syncCurrentText(newText)
                editText.setSelection(newText.length)
                performSearch(query, keepSearchFocus = true)
                Toast.makeText(this, "Replaced all occurrences", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No matches found to replace", Toast.LENGTH_SHORT).show()
            }
        }

        // Check recovery cache and restore if desired, otherwise execute normal load
        if (savedInstanceState == null) {
            checkAndRestoreRecovery {
                val filePath = intent.getStringExtra("file_path")
                if (filePath != null) {
                    currentFilePath = filePath
                    viewModel.openFile(filePath)
                } else {
                    viewModel.createNewDocument()
                }
            }
        }
    }

    /**
     * Sets up lifecycle-aware collection of UI state changes.
     * Updates views reactively when ViewModel state changes.
     */
    private fun setupUiStateCollection() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is EditorUiState.Idle -> {
                        progressBar.visibility = android.view.View.GONE
                    }

                    is EditorUiState.Loading -> {
                        progressBar.visibility = android.view.View.VISIBLE
                    }

                    is EditorUiState.Success -> {
                        progressBar.visibility = android.view.View.GONE
                        
                        val currentText = editText.text.toString()
                        if (currentText != state.file.content) {
                            isRestoringState = true
                            editText.setText(state.file.content)
                            isRestoringState = false
                        }
                        
                        // Set textWatcher initial state bounds
                        textWatcher.forceRecordInitialState(state.file.content)
                        
                        currentFilePath = state.file.path

                        // Initialize read-only mode from file system metadata quietly
                        isReadOnlyMode = state.file.isReadOnly
                        if (isReadOnlyMode) {
                            editText.keyListener = null
                            updateTitle(state.file.name)
                            btnToolbarUndo.isEnabled = false
                            btnToolbarUndo.alpha = 0.5f
                            btnToolbarRedo.isEnabled = false
                            btnToolbarRedo.alpha = 0.5f
                            findViewById<android.view.View>(R.id.btnReplace)?.isEnabled = false
                            findViewById<android.view.View>(R.id.btnReplaceAll)?.isEnabled = false
                            stopAutoCacheTimer()
                            clearRecoveryCache()
                            Toast.makeText(this@EditorActivity, R.string.msg_readonly_enabled, Toast.LENGTH_SHORT).show()
                        } else {
                            editText.keyListener = originalKeyListener
                            updateTitle(state.file.name)
                            updateUndoRedoButtons()
                            findViewById<android.view.View>(R.id.btnReplace)?.isEnabled = true
                            findViewById<android.view.View>(R.id.btnReplaceAll)?.isEnabled = true
                            clearRecoveryCache()
                            startAutoCacheTimer()
                        }
                        // Auto-detect syntax mode from extension
                        val fileNameLower = state.file.name.lowercase()
                        currentSyntaxMode = when {
                            fileNameLower.endsWith(".kt") || fileNameLower.endsWith(".kotlin") -> SyntaxMode.KOTLIN
                            fileNameLower.endsWith(".md") || fileNameLower.endsWith(".markdown") -> SyntaxMode.MARKDOWN
                            else -> SyntaxMode.PLAIN_TEXT
                        }
                        applySyntaxHighlight()

                        invalidateOptionsMenu()

                        state.message?.let { message ->
                            Toast.makeText(this@EditorActivity, message, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }

                    is EditorUiState.Error -> {
                        progressBar.visibility = android.view.View.GONE

                        val errorMessage = state.throwable.message ?: "Unknown error occurred"
                        Toast.makeText(
                            this@EditorActivity,
                            "Error: $errorMessage",
                            Toast.LENGTH_LONG
                        ).show()

                        android.util.Log.e("EditorActivity", "UI Error", state.throwable)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.encoding.collect {
                invalidateOptionsMenu()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_encoding)?.let { item ->
            item.title = getString(R.string.action_encoding, viewModel.encoding.value)
        }
        menu?.findItem(R.id.action_word_wrap)?.let { item ->
            item.isChecked = isWordWrapEnabled
        }
        menu?.findItem(R.id.action_read_only)?.let { item ->
            item.isChecked = isReadOnlyMode
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new -> {
                currentVersionName = null
                viewModel.createNewDocument()
                true
            }

            R.id.action_open -> {
                openDocumentLauncher.launch(arrayOf("text/*", "application/*", "*/*"))
                true
            }

            R.id.action_save_as -> {
                if (isReadOnlyMode) {
                    Toast.makeText(this, R.string.msg_readonly_blocked_save, Toast.LENGTH_SHORT).show()
                } else {
                    val defaultName = currentFilePath?.let {
                        getFileNameFromUri(android.net.Uri.parse(it))
                    } ?: "unnamed.txt"
                    createDocumentLauncher.launch(defaultName)
                }
                true
            }

            R.id.action_recents -> {
                showRecentFilesDialog()
                true
            }

            R.id.action_read_only -> {
                setReadOnly(!isReadOnlyMode)
                true
            }

            R.id.action_syntax_mode -> {
                showSyntaxModeSelectionDialog()
                true
            }

            R.id.action_encoding -> {
                showEncodingSelectionDialog()
                true
            }

            R.id.action_save_version -> {
                if (isReadOnlyMode) {
                    Toast.makeText(this, R.string.msg_readonly_blocked_version, Toast.LENGTH_SHORT).show()
                } else {
                    showSaveVersionDialog()
                }
                true
            }

            R.id.action_version_history -> {
                showVersionHistoryDialog()
                true
            }

            R.id.action_word_wrap -> {
                isWordWrapEnabled = !isWordWrapEnabled
                item.isChecked = isWordWrapEnabled
                editText.setHorizontallyScrolling(!isWordWrapEnabled)

                val selectionStart = editText.selectionStart
                val selectionEnd = editText.selectionEnd
                val inputType = editText.inputType
                editText.inputType = inputType
                editText.setSelection(selectionStart, selectionEnd)

                Toast.makeText(
                    this,
                    if (isWordWrapEnabled) "Word Wrap Enabled" else "Word Wrap Disabled",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }

            android.R.id.home -> {
                finish()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveCurrentFile() {
        val filePath = currentFilePath
        if (filePath == null || filePath == "unsaved://new_document") {
            createDocumentLauncher.launch("unnamed.txt")
            return
        }

        val name = getFileNameFromUri(android.net.Uri.parse(filePath)) ?: "document.txt"
        val content = editText.text.toString()

        viewModel.saveFile(name, filePath, content)
    }

    private fun showRecentFilesDialog() {
        val files = viewModel.recentFiles.value
            .filter { it.path.isNotBlank() && it.path != "unsaved://new_document" }
            .distinctBy { it.path }

        if (files.isEmpty()) {
            Toast.makeText(this, "No recent files", Toast.LENGTH_SHORT).show()
            return
        }

        val items = files.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Recent Files")
            .setItems(items) { _, which ->
                val selectedFile = files[which]
                currentFilePath = selectedFile.path
                currentVersionName = null
                viewModel.openFile(selectedFile.path)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEncodingSelectionDialog() {
        val encodings = arrayOf("UTF-8", "UTF-16", "US-ASCII")
        val currentEncoding = viewModel.encoding.value
        val checkedItem = encodings.indexOf(currentEncoding)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Encoding")
            .setSingleChoiceItems(encodings, checkedItem) { dialog, which ->
                viewModel.setEncoding(encodings[which])
                dialog.dismiss()
                Toast.makeText(this, "Encoding set to ${encodings[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex)
                    }
                }
            }
        }
        return uri.path?.let { java.io.File(it).name }
    }

    private fun updateUndoRedoButtons() {
        val canUndo = undoRedoManager.canUndo()
        val canRedo = undoRedoManager.canRedo()

        btnToolbarUndo.isEnabled = canUndo
        btnToolbarUndo.alpha = if (canUndo) 1.0f else 0.5f

        btnToolbarRedo.isEnabled = canRedo
        btnToolbarRedo.alpha = if (canRedo) 1.0f else 0.5f
    }

    private fun applyTextState(state: TextState) {
        isRestoringState = true
        editText.setText(state.text)
        try {
            val start = state.selectionStart.coerceIn(0, state.text.length)
            val end = state.selectionEnd.coerceIn(0, state.text.length)
            editText.setSelection(start, end)
        } catch (e: Exception) {
            editText.setSelection(state.text.length)
        }
        textWatcher.syncCurrentText(state.text)
        isRestoringState = false
    }

    private fun performUndo() {
        if (isReadOnlyMode) {
            Toast.makeText(this, R.string.msg_readonly_blocked_save, Toast.LENGTH_SHORT).show()
            return
        }
        val currentState = TextState(
            text = editText.text.toString(),
            selectionStart = editText.selectionStart,
            selectionEnd = editText.selectionEnd
        )
        val previousState = undoRedoManager.undo(currentState)
        if (previousState != null) {
            applyTextState(previousState)
            Toast.makeText(this, "Undo", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performRedo() {
        if (isReadOnlyMode) {
            Toast.makeText(this, R.string.msg_readonly_blocked_save, Toast.LENGTH_SHORT).show()
            return
        }
        val currentState = TextState(
            text = editText.text.toString(),
            selectionStart = editText.selectionStart,
            selectionEnd = editText.selectionEnd
        )
        val nextState = undoRedoManager.redo(currentState)
        if (nextState != null) {
            applyTextState(nextState)
            Toast.makeText(this, "Redo", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recordUndoStateBeforeAction() {
        undoRedoManager.pushState(TextState(
            text = editText.text.toString(),
            selectionStart = editText.selectionStart,
            selectionEnd = editText.selectionEnd
        ))
    }

    private fun performSearch(query: String, shouldHighlight: Boolean = true, keepSearchFocus: Boolean = true) {
        val prevIndex = currentMatchIndex
        searchMatches.clear()
        currentMatchIndex = -1
        lastSearchQuery = query

        if (query.isEmpty()) return

        val text = editText.text.toString()
        val isCaseSensitive = cbCaseSensitive.isChecked
        val isWholeWord = cbWholeWord.isChecked
        val isRegex = cbRegex.isChecked

        try {
            if (isRegex) {
                val options = if (isCaseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val pattern = query.toRegex(options)
                pattern.findAll(text).forEach { matchResult ->
                    val start = matchResult.range.first
                    if (!isWholeWord || isMatchWholeWord(text, start, matchResult.value.length)) {
                        searchMatches.add(start)
                    }
                }
            } else {
                var index = text.indexOf(query, 0, ignoreCase = !isCaseSensitive)
                while (index != -1) {
                    if (!isWholeWord || isMatchWholeWord(text, index, query.length)) {
                        searchMatches.add(index)
                    }
                    index = text.indexOf(query, index + 1, ignoreCase = !isCaseSensitive)
                }
            }
        } catch (e: Exception) {
            // Invalid regex
            return
        }

        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = if (prevIndex in searchMatches.indices) prevIndex else 0
            if (shouldHighlight) {
                highlightCurrentMatch(keepSearchFocus = keepSearchFocus)
            }
        }
    }

    private fun isMatchWholeWord(text: String, start: Int, length: Int): Boolean {
        val before = if (start > 0) text[start - 1] else ' '
        val after = if (start + length < text.length) text[start + length] else ' '
        
        fun isWordPart(c: Char) = c.isLetterOrDigit() || c == '_'
        
        return !isWordPart(before) && !isWordPart(after)
    }

    private fun toggleSearchPanel() {
        if (searchPanel.visibility == android.view.View.VISIBLE) {
            searchPanel.visibility = android.view.View.GONE
            editText.setSelection(editText.selectionStart)
        } else {
            searchPanel.visibility = android.view.View.VISIBLE
            searchQuery.requestFocus()
        }
    }

    private fun highlightCurrentMatch(keepSearchFocus: Boolean = true) {
        if (currentMatchIndex in searchMatches.indices) {
            val start = searchMatches[currentMatchIndex]
            val end = start + lastSearchQuery.length
            val textLength = editText.text.length

            if (start >= 0 && end <= textLength) {
                if (!keepSearchFocus) {
                    editText.requestFocus()
                }
                editText.setSelection(start, end)

                if (keepSearchFocus) {
                    val selStart = searchQuery.selectionStart
                    val selEnd = searchQuery.selectionEnd
                    searchQuery.requestFocus()
                    searchQuery.setSelection(selStart, selEnd)
                }
            } else {
                // Bounds are invalid (text mutated under position). Refresh match positions.
                performSearch(lastSearchQuery, keepSearchFocus = keepSearchFocus)
            }
        }
    }

    private fun navigateMatches(forward: Boolean) {
        // Re-run search query to make sure match positions are fresh
        performSearch(searchQuery.text.toString(), shouldHighlight = false, keepSearchFocus = true)

        if (searchMatches.isEmpty()) {
            Toast.makeText(this, "No matches found", Toast.LENGTH_SHORT).show()
            return
        }

        if (forward) {
            currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
        } else {
            currentMatchIndex = if (currentMatchIndex <= 0) {
                searchMatches.size - 1
            } else {
                currentMatchIndex - 1
            }
        }
        highlightCurrentMatch(keepSearchFocus = true)
    }

    private fun showSaveVersionDialog() {
        val filePath = currentFilePath
        if (filePath == null || filePath == "unsaved://new_document") {
            Toast.makeText(this, "Please save the file first before creating a version snapshot", Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this)
        input.hint = getString(R.string.prompt_save_version_hint)
        input.setTextColor(android.graphics.Color.parseColor("#6750a4")) // Purple
        input.setHintTextColor(android.graphics.Color.parseColor("#625b71")) // Secondary Purple

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin / 2, margin, margin / 2)
        input.layoutParams = params
        container.addView(input)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.prompt_save_version_title)
            .setMessage(R.string.prompt_save_version_message)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val commitMessage = input.text.toString().trim()
                if (commitMessage.isNotEmpty()) {
                    currentVersionName = "v: $commitMessage"
                    viewModel.saveVersion(commitMessage, editText.text.toString())
                } else {
                    Toast.makeText(this, "Description cannot be empty", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showVersionHistoryDialog() {
        val filePath = currentFilePath
        if (filePath == null || filePath == "unsaved://new_document") {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val versions = viewModel.getVersionHistorySync(filePath)

            if (versions.isEmpty()) {
                Toast.makeText(this@EditorActivity, R.string.msg_no_versions, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val items = versions.map { version ->
                val dateStr = dateFormat.format(java.util.Date(version.timestamp))
                "v${version.versionId}: ${version.commitMessage}\n($dateStr)"
            }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this@EditorActivity)
                .setTitle(R.string.dialog_version_history_title)
                .setItems(items) { _, which ->
                    val selectedVersion = versions[which]
                    val options = arrayOf(
                        getString(R.string.action_compare_previous),
                        getString(R.string.action_compare_current),
                        getString(R.string.action_compare_another),
                        getString(R.string.btn_restore)
                    )
                    androidx.appcompat.app.AlertDialog.Builder(this@EditorActivity)
                        .setTitle("Version v${selectedVersion.versionId} Actions")
                        .setItems(options) { _, optionIdx ->
                            when (optionIdx) {
                                0 -> showVersionDetailsDialog(selectedVersion)
                                1 -> compareVersionWithCurrent(selectedVersion)
                                2 -> showCompareWithAnotherPicker(selectedVersion, versions)
                                3 -> restoreVersion(selectedVersion)
                            }
                        }
                        .show()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun showCompareWithAnotherPicker(base: com.editor.core.features.editor.domain.FileVersion, all: List<com.editor.core.features.editor.domain.FileVersion>) {
        val otherVersions = all.filter { it.versionId != base.versionId }
        if (otherVersions.isEmpty()) {
            Toast.makeText(this, "No other versions to compare against", Toast.LENGTH_SHORT).show()
            return
        }

        val items = otherVersions.map { "v${it.versionId}: ${it.commitMessage}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Compare v${base.versionId} with...")
            .setItems(items) { _, which ->
                compareTwoHistoricalVersions(base, otherVersions[which])
            }
            .show()
    }

    private fun compareTwoHistoricalVersions(v1: com.editor.core.features.editor.domain.FileVersion, v2: com.editor.core.features.editor.domain.FileVersion) {
        val filePath = currentFilePath ?: return
        lifecycleScope.launch {
            try {
                val text1 = viewModel.getVersionContent(filePath, v1.versionId)
                val text2 = viewModel.getVersionContent(filePath, v2.versionId)
                // Determine chronological order for meaningful +/-
                val (older, newer) = if (v1.versionId < v2.versionId) v1 to v2 else v2 to v1
                val olderText = if (v1.versionId < v2.versionId) text1 else text2
                val newerText = if (v1.versionId < v2.versionId) text2 else text1
                
                val diff = com.editor.core.features.versioncontrol.DiffEngine.computeDiff(olderText, newerText)
                showDiffDialog("Compare: v${older.versionId} vs v${newer.versionId}", diff)
            } catch (e: Exception) {
                Toast.makeText(this@EditorActivity, "Failed to compare versions", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun compareVersionWithCurrent(version: com.editor.core.features.editor.domain.FileVersion) {
        val filePath = currentFilePath ?: return
        lifecycleScope.launch {
            try {
                val oldText = viewModel.getVersionContent(filePath, version.versionId)
                val newText = editText.text.toString()
                val diff = com.editor.core.features.versioncontrol.DiffEngine.computeDiff(oldText, newText)
                showDiffDialog("Compare: v${version.versionId} vs Current", diff)
            } catch (e: Exception) {
                Toast.makeText(this@EditorActivity, "Failed to compute diff", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restoreVersion(version: com.editor.core.features.editor.domain.FileVersion) {
        val filePath = currentFilePath ?: return
        if (isReadOnlyMode) {
            Toast.makeText(this@EditorActivity, R.string.msg_readonly_blocked_restore, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                currentVersionName = "v${version.versionId}: ${version.commitMessage}"
                val reconstructedText = viewModel.getVersionContent(filePath, version.versionId)
                viewModel.rollBackToVersion(filePath, reconstructedText, "Rollback to version v${version.versionId}: ${version.commitMessage}")
                Toast.makeText(this@EditorActivity, "Version restored successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@EditorActivity, R.string.msg_error_rollback, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showVersionDetailsDialog(version: com.editor.core.features.editor.domain.FileVersion) {
        val diffText = version.patchContent.ifBlank { "No changes (Identical content)" }
        showDiffDialog(getString(R.string.dialog_diff_title, "v${version.versionId}"), diffText)
    }

    private fun showDiffDialog(title: String, diffText: String) {
        val textView = android.widget.TextView(this)
        textView.text = highlightDiff(diffText)
        textView.typeface = android.graphics.Typeface.MONOSPACE
        textView.textSize = 13f

        val scroll = android.widget.ScrollView(this)
        val margin = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(margin, margin, margin, margin)
        scroll.addView(textView)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun highlightDiff(text: String): android.text.SpannableStringBuilder {
        val spannable = android.text.SpannableStringBuilder()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val nextLine = if (i + 1 < lines.size) lines[i + 1] else null
            
            val start = spannable.length
            
            // Detect "Change" (Deletion followed by Insertion)
            if (line.startsWith("- ") && nextLine != null && nextLine.startsWith("+ ")) {
                // Highlight deletion part
                spannable.append(line).append("\n")
                applyLineHighlight(spannable, start, line, "#F44336", "#FFEBEE") // Red
                
                // Highlight insertion part (in Yellow to indicate "Change")
                val start2 = spannable.length
                spannable.append(nextLine).append("\n")
                applyLineHighlight(spannable, start2, nextLine, "#FBC02D", "#FFFDE7") // Yellow
                
                i += 2
                continue
            }

            spannable.append(line).append("\n")
            when {
                line.startsWith("+ ") -> applyLineHighlight(spannable, start, line, "#4CAF50", "#E8F5E9") // Green
                line.startsWith("- ") -> applyLineHighlight(spannable, start, line, "#F44336", "#FFEBEE") // Red
                line.startsWith("  ") -> {
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#757575")),
                        start,
                        spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            i++
        }
        return spannable
    }

    private fun applyLineHighlight(spannable: android.text.SpannableStringBuilder, start: Int, line: String, textColor: String, bgColor: String) {
        val end = start + line.length
        // Text Color
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(textColor)),
            start,
            end + 1,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // Background Color
        spannable.setSpan(
            android.text.style.BackgroundColorSpan(android.graphics.Color.parseColor(bgColor)),
            start,
            end + 1,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun startAutoCacheTimer() {
        stopAutoCacheTimer()
        autoCacheJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(10000)
                val path = currentFilePath
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    editText.text.toString()
                }

                if (path != null && text.isNotEmpty()) {
                    try {
                        recoveryCacheFile.writeText("$path\n$text")
                        android.util.Log.d("EditorActivity", "Auto-cached active text buffer")
                    } catch (e: Exception) {
                        android.util.Log.e("EditorActivity", "Auto-cache write failure", e)
                    }
                }
            }
        }
    }

    private fun stopAutoCacheTimer() {
        autoCacheJob?.cancel()
        autoCacheJob = null
    }

    private fun clearRecoveryCache() {
        try {
            if (recoveryCacheFile.exists()) {
                recoveryCacheFile.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "Recovery cache clear failure", e)
        }
    }

    private fun checkAndRestoreRecovery(onNoRecovery: () -> Unit) {
        if (!recoveryCacheFile.exists()) {
            onNoRecovery()
            return
        }

        try {
            val lines = recoveryCacheFile.readLines()
            if (lines.isEmpty()) {
                clearRecoveryCache()
                onNoRecovery()
                return
            }

            val path = lines[0]
            val content = lines.drop(1).joinToString("\n")

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_recovery_title)
                .setMessage(R.string.dialog_recovery_message)
                .setCancelable(false)
                .setPositiveButton(R.string.btn_restore) { dialog, _ ->
                    currentFilePath = path
                    editText.setText(content)

                    val name = getFileNameFromUri(android.net.Uri.parse(path)) ?: "document.txt"
                    supportActionBar?.title = name
                    textWatcher.forceRecordInitialState(content)

                    clearRecoveryCache()
                    startAutoCacheTimer()

                    Toast.makeText(this, R.string.msg_recovery_restored, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.btn_discard) { dialog, _ ->
                    clearRecoveryCache()
                    Toast.makeText(this, R.string.msg_recovery_discarded, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onNoRecovery()
                }
                .show()
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "Recovery restore failed, clearing cache", e)
            clearRecoveryCache()
            onNoRecovery()
        }
    }

    private fun setReadOnly(enabled: Boolean) {
        isReadOnlyMode = enabled

        if (enabled) {
            editText.keyListener = null

            // Update title with status
            val name = currentFilePath?.let { getFileNameFromUri(android.net.Uri.parse(it)) } ?: "Text Editor"
            updateTitle(name)

            // Disable undo/redo buttons
            btnToolbarUndo.isEnabled = false
            btnToolbarUndo.alpha = 0.5f
            btnToolbarRedo.isEnabled = false
            btnToolbarRedo.alpha = 0.5f

            // Disable replace buttons in search panel
            findViewById<android.view.View>(R.id.btnReplace)?.isEnabled = false
            findViewById<android.view.View>(R.id.btnReplaceAll)?.isEnabled = false

            // Stop auto-cache loop
            stopAutoCacheTimer()

            Toast.makeText(this, R.string.msg_readonly_enabled, Toast.LENGTH_SHORT).show()
        } else {
            editText.keyListener = originalKeyListener

            // Update title with status
            val name = currentFilePath?.let { getFileNameFromUri(android.net.Uri.parse(it)) } ?: "Text Editor"
            updateTitle(name)

            // Enable/disable undo/redo buttons dynamically based on history
            updateUndoRedoButtons()

            // Enable replace buttons in search panel
            findViewById<android.view.View>(R.id.btnReplace)?.isEnabled = true
            findViewById<android.view.View>(R.id.btnReplaceAll)?.isEnabled = true

            // Restart auto-cache loop
            startAutoCacheTimer()

            Toast.makeText(this, R.string.msg_readonly_disabled, Toast.LENGTH_SHORT).show()
        }

        // Redraw options menu to update state checkable status
        invalidateOptionsMenu()
    }

    private fun updateTitle(fileName: String) {
        val versionSuffix = if (currentVersionName != null) " [$currentVersionName]" else ""
        val readOnlySuffix = if (isReadOnlyMode) " [Read Only]" else ""
        supportActionBar?.title = "$fileName$versionSuffix$readOnlySuffix"
    }

    private fun scheduleSyntaxHighlight() {
        syntaxHighlightJob?.cancel()
        syntaxHighlightJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(300)
            applySyntaxHighlight()
        }
    }

    private fun applySyntaxHighlight() {
        if (currentSyntaxMode == SyntaxMode.PLAIN_TEXT) {
            clearAllSyntaxSpans()
            return
        }

        isApplyingSyntaxHighlight = true
        val text = editText.editableText
        val cursorStart = editText.selectionStart
        val cursorEnd = editText.selectionEnd

        // Clear existing syntax styling spans
        val spans = text.getSpans(0, text.length, android.text.style.CharacterStyle::class.java)
        for (span in spans) {
            if (span is android.text.style.ForegroundColorSpan ||
                span is android.text.style.StyleSpan ||
                span is android.text.style.TypefaceSpan) {
                text.removeSpan(span)
            }
        }

        if (currentSyntaxMode == SyntaxMode.KOTLIN) {
            applyKotlinHighlighting(text)
        } else if (currentSyntaxMode == SyntaxMode.MARKDOWN) {
            applyMarkdownHighlighting(text)
        }

        // Restore cursor
        try {
            editText.setSelection(cursorStart.coerceIn(0, text.length), cursorEnd.coerceIn(0, text.length))
        } catch (e: Exception) {
            // Safety guard
        }

        isApplyingSyntaxHighlight = false
    }

    private fun clearAllSyntaxSpans() {
        isApplyingSyntaxHighlight = true
        val text = editText.editableText
        val spans = text.getSpans(0, text.length, android.text.style.CharacterStyle::class.java)
        for (span in spans) {
            if (span is android.text.style.ForegroundColorSpan ||
                span is android.text.style.StyleSpan ||
                span is android.text.style.TypefaceSpan) {
                text.removeSpan(span)
            }
        }
        isApplyingSyntaxHighlight = false
    }

    private fun applyKotlinHighlighting(text: android.text.Spannable) {
        // 1. Keywords
        kotlinKeywordRegex.findAll(text).forEach { match ->
            text.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#2196F3")), // Blue
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 2. Strings
        kotlinStringRegex.findAll(text).forEach { match ->
            text.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#4CAF50")), // Green
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 3. Comments
        kotlinCommentRegex.findAll(text).forEach { match ->
            text.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#9E9E9E")), // Gray
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyMarkdownHighlighting(text: android.text.Spannable) {
        // 1. Headings
        markdownHeadingRegex.findAll(text).forEach { match ->
            text.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#9C27B0")), // Purple
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 2. Bold
        markdownBoldRegex.findAll(text).forEach { match ->
            text.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 3. Inline Code
        markdownCodeRegex.findAll(text).forEach { match ->
            text.setSpan(
                android.text.style.TypefaceSpan("monospace"),
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#E91E63")), // Pink
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 4. Lists
        markdownListRegex.findAll(text).forEach { match ->
            text.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FF9800")), // Orange
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun showSyntaxModeSelectionDialog() {
        val modes = arrayOf("Plain Text", "Kotlin", "Markdown")
        val checkedItem = currentSyntaxMode.ordinal

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_syntax_mode_title)
            .setSingleChoiceItems(modes, checkedItem) { dialog, which ->
                currentSyntaxMode = SyntaxMode.values()[which]
                applySyntaxHighlight()

                val modeName = modes[which]
                Toast.makeText(
                    this@EditorActivity,
                    getString(R.string.msg_syntax_mode_changed, modeName),
                    Toast.LENGTH_SHORT
                ).show()

                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (currentFilePath != null && !isReadOnlyMode) {
            startAutoCacheTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoCacheTimer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current_file_path", currentFilePath)
        outState.putBoolean("is_read_only", isReadOnlyMode)
        outState.putInt("syntax_mode", currentSyntaxMode.ordinal)
        outState.putBoolean("word_wrap", isWordWrapEnabled)
        outState.putString("current_version_name", currentVersionName)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentFilePath = savedInstanceState.getString("current_file_path")
        isReadOnlyMode = savedInstanceState.getBoolean("is_read_only")
        currentSyntaxMode = SyntaxMode.values()[savedInstanceState.getInt("syntax_mode")]
        isWordWrapEnabled = savedInstanceState.getBoolean("word_wrap")
        currentVersionName = savedInstanceState.getString("current_version_name")
        
        // Restore UI states based on loaded variables
        if (isReadOnlyMode) {
            setReadOnly(true)
        } else {
            val name = currentFilePath?.let { getFileNameFromUri(android.net.Uri.parse(it)) } ?: "Text Editor"
            updateTitle(name)
        }
        
        editText.setHorizontallyScrolling(!isWordWrapEnabled)
        applySyntaxHighlight()
    }
}
