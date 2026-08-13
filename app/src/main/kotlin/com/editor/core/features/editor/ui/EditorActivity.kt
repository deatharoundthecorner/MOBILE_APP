package com.editor.core.features.editor.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.editor.core.R
import com.editor.core.data.local.AppDatabase
import com.editor.core.data.repository.DiskFileRepository
import com.editor.core.features.editor.ui.helper.RecoveryManager
import com.editor.core.features.editor.ui.helper.SearchHelper
import com.editor.core.features.editor.ui.helper.SyntaxHighlighter
import com.editor.core.features.editor.ui.helper.SyntaxMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Integrates Member 1 (File operations, Undo/Redo, Read-only),
 * Member 2 (Version Control, Room DB, Diff Engine), and
 * Member 3 (Search & Replace Engine, Syntax Highlighting, Unsaved Change Recovery, Editor UX).
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
    private lateinit var txtMatchCount: TextView
    private lateinit var txtEditorStatus: TextView
    private lateinit var cbCaseSensitive: CheckBox
    private lateinit var cbWholeWord: CheckBox
    private lateinit var cbRegex: CheckBox

    // Member 3 Helper Classes
    private val searchHelper = SearchHelper()
    private val syntaxHighlighter = SyntaxHighlighter()
    private val recoveryManager by lazy { RecoveryManager(this) }

    // Version tracking
    private var currentVersionName: String? = null

    // Toolbar Undo/Redo/Save/Search views
    private lateinit var btnToolbarUndo: ImageButton
    private lateinit var btnToolbarRedo: ImageButton
    private lateinit var btnToolbarSave: ImageButton
    private lateinit var btnToolbarSearch: ImageButton

    // Cache recovery variables
    private var autoCacheJob: Job? = null

    // Read-only Mode variables
    private var isReadOnlyMode = false
    private var originalKeyListener: android.text.method.KeyListener? = null

    // Syntax Highlighting variables
    private var currentSyntaxMode = SyntaxMode.PLAIN_TEXT
    private var isApplyingSyntaxHighlight = false
    private var syntaxHighlightJob: Job? = null

    private var currentFilePath: String? = null

    // Undo/Redo tracking properties
    private val undoRedoManager = UndoRedoManager()
    private var isRestoringState = false

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
                if (searchPanel.visibility == android.view.View.VISIBLE && searchHelper.lastQuery.isNotEmpty()) {
                    performSearch(searchHelper.lastQuery)
                }
                updateEditorStatusBar()
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
            updateEditorStatusBar()
        }

        fun syncCurrentText(text: String) {
            lastText = text
            lastSelectionStart = editText.selectionStart
            lastSelectionEnd = editText.selectionEnd
            updateUndoRedoButtons()
            invalidateOptionsMenu()
            updateEditorStatusBar()
        }
    }

    private fun persistUriPermission(uri: android.net.Uri, writable: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Ignore if provider doesn't support persistable permission
        }

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
        txtMatchCount = findViewById(R.id.txtMatchCount)
        txtEditorStatus = findViewById(R.id.txtEditorStatus)
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
        editText.setOnClickListener { updateEditorStatusBar() }

        // Initialize button enabled/alpha states
        updateUndoRedoButtons()
        updateEditorStatusBar()

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
            searchHelper.clearHighlights(editText.editableText)
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

            if (searchHelper.getCurrentMatch() == null) {
                Toast.makeText(this, "No active match selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            recordUndoStateBeforeAction()
            isRestoringState = true
            val success = searchHelper.replaceCurrent(editText.editableText, replaceText.text.toString())
            isRestoringState = false

            if (success) {
                textWatcher.syncCurrentText(editText.text.toString())
                performSearch(searchHelper.lastQuery, keepSearchFocus = true)
                Toast.makeText(this, "Replaced", Toast.LENGTH_SHORT).show()
            }
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

            val (newText, count) = searchHelper.replaceAll(text, replacement)
            if (count > 0) {
                recordUndoStateBeforeAction()
                isRestoringState = true
                editText.setText(newText)
                isRestoringState = false

                textWatcher.syncCurrentText(newText)
                performSearch(query, keepSearchFocus = true)
                Toast.makeText(this, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No matches found to replace", Toast.LENGTH_SHORT).show()
            }
        }

        // Check recovery cache and restore if desired, otherwise execute normal load
        if (savedInstanceState == null) {
            recoveryManager.checkAndPromptRecovery(
                onRestore = { path, content ->
                    currentFilePath = path
                    editText.setText(content)
                    val name = getFileNameFromUri(android.net.Uri.parse(path)) ?: "document.txt"
                    updateTitle(name)
                    textWatcher.forceRecordInitialState(content)
                    startAutoCacheTimer()
                    Toast.makeText(this, R.string.msg_recovery_restored, Toast.LENGTH_SHORT).show()
                },
                onDiscard = {
                    val filePath = intent.getStringExtra("file_path")
                    if (filePath != null) {
                        currentFilePath = filePath
                        viewModel.openFile(filePath)
                    } else {
                        viewModel.createNewDocument()
                    }
                }
            )
        }
    }

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

                        textWatcher.forceRecordInitialState(state.file.content)
                        currentFilePath = state.file.path

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
                            recoveryManager.clearRecoveryCache()
                            Toast.makeText(this@EditorActivity, R.string.msg_readonly_enabled, Toast.LENGTH_SHORT).show()
                        } else {
                            editText.keyListener = originalKeyListener
                            updateTitle(state.file.name)
                            updateUndoRedoButtons()
                            findViewById<android.view.View>(R.id.btnReplace)?.isEnabled = true
                            findViewById<android.view.View>(R.id.btnReplaceAll)?.isEnabled = true
                            recoveryManager.clearRecoveryCache()
                            startAutoCacheTimer()
                        }

                        currentSyntaxMode = SyntaxHighlighter.detectMode(state.file.name)
                        applySyntaxHighlight()

                        invalidateOptionsMenu()

                        state.message?.let { message ->
                            Toast.makeText(this@EditorActivity, message, Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
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

    private fun performSearch(query: String, keepSearchFocus: Boolean = true) {
        searchHelper.isCaseSensitive = cbCaseSensitive.isChecked
        searchHelper.isWholeWord = cbWholeWord.isChecked
        searchHelper.isRegex = cbRegex.isChecked

        val success = searchHelper.search(editText.text.toString(), query)
        if (!success) {
            txtMatchCount.text = "Invalid regex"
            searchHelper.clearHighlights(editText.editableText)
            return
        }

        txtMatchCount.text = searchHelper.getStatusText()
        searchHelper.applyHighlights(editText.editableText)

        val match = searchHelper.getCurrentMatch()
        if (match != null) {
            if (!keepSearchFocus) {
                editText.requestFocus()
            }
            editText.setSelection(match.start, match.end)

            if (keepSearchFocus) {
                val selStart = searchQuery.selectionStart
                val selEnd = searchQuery.selectionEnd
                searchQuery.requestFocus()
                searchQuery.setSelection(selStart, selEnd)
            }
        }
    }

    private fun toggleSearchPanel() {
        if (searchPanel.visibility == android.view.View.VISIBLE) {
            searchPanel.visibility = android.view.View.GONE
            searchHelper.clearHighlights(editText.editableText)
            editText.setSelection(editText.selectionStart)
        } else {
            searchPanel.visibility = android.view.View.VISIBLE
            searchQuery.requestFocus()
        }
    }

    private fun navigateMatches(forward: Boolean) {
        searchHelper.isCaseSensitive = cbCaseSensitive.isChecked
        searchHelper.isWholeWord = cbWholeWord.isChecked
        searchHelper.isRegex = cbRegex.isChecked

        searchHelper.search(editText.text.toString(), searchQuery.text.toString())
        val match = searchHelper.navigate(forward)

        txtMatchCount.text = searchHelper.getStatusText()
        searchHelper.applyHighlights(editText.editableText)

        if (match != null) {
            val selStart = searchQuery.selectionStart
            val selEnd = searchQuery.selectionEnd
            editText.setSelection(match.start, match.end)
            searchQuery.requestFocus()
            searchQuery.setSelection(selStart, selEnd)
        } else {
            Toast.makeText(this, "No matches found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveVersionDialog() {
        val filePath = currentFilePath
        if (filePath == null || filePath == "unsaved://new_document") {
            Toast.makeText(this, "Please save the file first before creating a version snapshot", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this)
        input.hint = getString(R.string.prompt_save_version_hint)
        input.setTextColor(android.graphics.Color.parseColor("#6750a4"))
        input.setHintTextColor(android.graphics.Color.parseColor("#625b71"))

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin / 2, margin, margin / 2)
        input.layoutParams = params
        container.addView(input)

        AlertDialog.Builder(this)
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

            AlertDialog.Builder(this@EditorActivity)
                .setTitle(R.string.dialog_version_history_title)
                .setItems(items) { _, which ->
                    val selectedVersion = versions[which]
                    val options = arrayOf(
                        getString(R.string.action_compare_previous),
                        getString(R.string.action_compare_current),
                        getString(R.string.action_compare_another),
                        getString(R.string.btn_restore)
                    )
                    AlertDialog.Builder(this@EditorActivity)
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
        AlertDialog.Builder(this)
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
        val textView = TextView(this)
        textView.text = highlightDiff(diffText)
        textView.typeface = android.graphics.Typeface.MONOSPACE
        textView.textSize = 13f

        val scroll = android.widget.ScrollView(this)
        val margin = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(margin, margin, margin, margin)
        scroll.addView(textView)

        AlertDialog.Builder(this)
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

            if (line.startsWith("- ") && nextLine != null && nextLine.startsWith("+ ")) {
                spannable.append(line).append("\n")
                applyLineHighlight(spannable, start, line, "#F44336", "#FFEBEE")

                val start2 = spannable.length
                spannable.append(nextLine).append("\n")
                applyLineHighlight(spannable, start2, nextLine, "#FBC02D", "#FFFDE7")

                i += 2
                continue
            }

            spannable.append(line).append("\n")
            when {
                line.startsWith("+ ") -> applyLineHighlight(spannable, start, line, "#4CAF50", "#E8F5E9")
                line.startsWith("- ") -> applyLineHighlight(spannable, start, line, "#F44336", "#FFEBEE")
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
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(textColor)),
            start, end + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.BackgroundColorSpan(android.graphics.Color.parseColor(bgColor)),
            start, end + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun startAutoCacheTimer() {
        stopAutoCacheTimer()
        autoCacheJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10000)
                val path = currentFilePath
                val text = withContext(Dispatchers.Main) {
                    editText.text.toString()
                }

                if (path != null && text.isNotEmpty()) {
                    recoveryManager.saveRecoveryCache(path, text)
                }
            }
        }
    }

    private fun stopAutoCacheTimer() {
        autoCacheJob?.cancel()
        autoCacheJob = null
    }

    private fun setReadOnly(enabled: Boolean) {
        isReadOnlyMode = enabled

        if (enabled) {
            editText.keyListener = null

            val name = currentFilePath?.let { getFileNameFromUri(android.net.Uri.parse(it)) } ?: "Text Editor"
            updateTitle(name)

            btnToolbarUndo.isEnabled = false
            btnToolbarUndo.alpha = 0.5f
            btnToolbarRedo.isEnabled = false
            btnToolbarRedo.alpha = 0.5f

            findViewById<android.view.View>(R.id.btnReplace)?.isEnabled = false
            findViewById<android.view.View>(R.id.btnReplaceAll)?.isEnabled = false

            stopAutoCacheTimer()
            Toast.makeText(this, R.string.msg_readonly_enabled, Toast.LENGTH_SHORT).show()
        } else {
            editText.keyListener = originalKeyListener

            val name = currentFilePath?.let { getFileNameFromUri(android.net.Uri.parse(it)) } ?: "Text Editor"
            updateTitle(name)

            updateUndoRedoButtons()

            findViewById<android.view.View>(R.id.btnReplace)?.isEnabled = true
            findViewById<android.view.View>(R.id.btnReplaceAll)?.isEnabled = true

            startAutoCacheTimer()
            Toast.makeText(this, R.string.msg_readonly_disabled, Toast.LENGTH_SHORT).show()
        }

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
            delay(300)
            applySyntaxHighlight()
        }
    }

    private fun applySyntaxHighlight() {
        isApplyingSyntaxHighlight = true
        val cursorStart = editText.selectionStart
        val cursorEnd = editText.selectionEnd

        syntaxHighlighter.applySyntaxHighlighting(editText.editableText, currentSyntaxMode)

        try {
            editText.setSelection(
                cursorStart.coerceIn(0, editText.length()),
                cursorEnd.coerceIn(0, editText.length())
            )
        } catch (e: Exception) {
            // Guard
        }

        isApplyingSyntaxHighlight = false
    }

    private fun showSyntaxModeSelectionDialog() {
        val modes = arrayOf("Plain Text", "Kotlin", "Markdown")
        val checkedItem = currentSyntaxMode.ordinal

        AlertDialog.Builder(this)
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

        if (isReadOnlyMode) {
            setReadOnly(true)
        } else {
            val name = currentFilePath?.let { getFileNameFromUri(android.net.Uri.parse(it)) } ?: "Text Editor"
            updateTitle(name)
        }

        editText.setHorizontallyScrolling(!isWordWrapEnabled)
        applySyntaxHighlight()
        updateEditorStatusBar()
    }

    private fun updateEditorStatusBar() {
        val text = editText.text.toString()
        val sel = editText.selectionStart.coerceIn(0, text.length)

        var line = 1
        var col = 1
        for (i in 0 until sel) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }

        val totalLines = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
        val totalChars = text.length

        txtEditorStatus.text = "Ln $line, Col $col | $totalChars chars | $totalLines lines"
    }
}
