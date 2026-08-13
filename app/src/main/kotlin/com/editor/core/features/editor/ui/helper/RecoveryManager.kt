package com.editor.core.features.editor.ui.helper

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.editor.core.R
import java.io.File

/**
 * Helper class managing unsaved-change recovery cache operations and dialogs.
 * Ensures recovery cache persistence, clean recovery prompt execution, and cleanup.
 */
class RecoveryManager(private val context: Context) {

    private val recoveryCacheFile by lazy {
        File(context.cacheDir, "recovery_cache.tmp")
    }

    /**
     * Checks if an unsaved change recovery cache exists on disk.
     */
    fun hasRecoveryCache(): Boolean {
        return recoveryCacheFile.exists() && recoveryCacheFile.length() > 0
    }

    /**
     * Writes the current active file path and text content to the recovery cache file.
     */
    fun saveRecoveryCache(filePath: String, content: String): Boolean {
        if (filePath.isBlank() || content.isEmpty()) return false
        return try {
            recoveryCacheFile.writeText("$filePath\n$content")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads recovery cache from disk. Returns Pair(filePath, content) or null if invalid.
     */
    fun readRecoveryCache(): Pair<String, String>? {
        if (!hasRecoveryCache()) return null
        return try {
            val lines = recoveryCacheFile.readLines()
            if (lines.isEmpty()) {
                clearRecoveryCache()
                return null
            }
            val filePath = lines[0]
            val content = lines.drop(1).joinToString("\n")
            Pair(filePath, content)
        } catch (e: Exception) {
            clearRecoveryCache()
            null
        }
    }

    /**
     * Deletes the recovery cache file from disk.
     */
    fun clearRecoveryCache(): Boolean {
        return try {
            if (recoveryCacheFile.exists()) {
                recoveryCacheFile.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shows Material 3 recovery prompt dialog if unsaved recovery cache exists.
     */
    fun checkAndPromptRecovery(
        onRestore: (filePath: String, content: String) -> Unit,
        onDiscard: () -> Unit
    ) {
        val recoveryData = readRecoveryCache()
        if (recoveryData == null) {
            onDiscard()
            return
        }

        val (filePath, content) = recoveryData

        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_recovery_title)
            .setMessage(R.string.dialog_recovery_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_restore) { dialog, _ ->
                clearRecoveryCache()
                onRestore(filePath, content)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_discard) { dialog, _ ->
                clearRecoveryCache()
                onDiscard()
                dialog.dismiss()
            }
            .show()
    }
}
