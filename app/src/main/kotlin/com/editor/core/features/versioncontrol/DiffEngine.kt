package com.editor.core.features.versioncontrol

/**
 * Pure Kotlin utility for computing and applying text diffs.
 * Implements line-based and grouped character-based diff computation for version control.
 */
object DiffEngine {

    /**
     * Computes a diff format patch between two text versions.
     * For multi-line text, uses line-by-line comparison.
     * For single-line text, uses grouped character-by-character comparison to ensure minimal and readable deltas.
     */
    fun computeDiff(oldText: String, newText: String): String {
        if (oldText == newText) {
            return ""
        }

        // If both are single line, use character-level diff to meet specific requirements like "+ World"
        if (!oldText.contains('\n') && !newText.contains('\n')) {
            return computeCompactCharDiff(oldText, newText)
        }

        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')

        val diffLines = mutableListOf<String>()
        val lcs = computeLongestCommonSubsequence(oldLines, newLines)
        var oldIdx = 0
        var newIdx = 0

        for (commonLine in lcs) {
            while (oldIdx < oldLines.size && oldLines[oldIdx] != commonLine) {
                diffLines.add("- ${oldLines[oldIdx]}")
                oldIdx++
            }
            while (newIdx < newLines.size && newLines[newIdx] != commonLine) {
                diffLines.add("+ ${newLines[newIdx]}")
                newIdx++
            }
            if (oldIdx < oldLines.size) oldIdx++
            if (newIdx < newLines.size) newIdx++
            diffLines.add("  $commonLine")
        }

        while (oldIdx < oldLines.size) {
            diffLines.add("- ${oldLines[oldIdx]}")
            oldIdx++
        }
        while (newIdx < newLines.size) {
            diffLines.add("+ ${newLines[newIdx]}")
            newIdx++
        }

        return diffLines.joinToString("\n")
    }

    /**
     * Computes a character-level diff but groups consecutive additions/deletions for readability.
     * Output format: COMPACT_CHAR_DIFF followed by grouped +/- entries.
     */
    private fun computeCompactCharDiff(oldText: String, newText: String): String {
        val oldChars = oldText.map { it.toString() }
        val newChars = newText.map { it.toString() }
        val lcs = computeLongestCommonSubsequence(oldChars, newChars)
        
        val diffGroups = mutableListOf<String>()
        var oldIdx = 0
        var newIdx = 0

        fun flushGroups(pendingDeletes: StringBuilder, pendingInsertions: StringBuilder) {
            if (pendingDeletes.isNotEmpty()) {
                diffGroups.add("- $pendingDeletes")
                pendingDeletes.clear()
            }
            if (pendingInsertions.isNotEmpty()) {
                diffGroups.add("+ $pendingInsertions")
                pendingInsertions.clear()
            }
        }

        val pendingDeletes = StringBuilder()
        val pendingInsertions = StringBuilder()

        for (common in lcs) {
            while (oldIdx < oldChars.size && oldChars[oldIdx] != common) {
                pendingDeletes.append(oldChars[oldIdx])
                oldIdx++
            }
            while (newIdx < newChars.size && newChars[newIdx] != common) {
                pendingInsertions.append(newChars[newIdx])
                newIdx++
            }
            
            flushGroups(pendingDeletes, pendingInsertions)
            
            diffGroups.add("  $common")
            oldIdx++
            newIdx++
        }
        
        while (oldIdx < oldChars.size) {
            pendingDeletes.append(oldChars[oldIdx])
            oldIdx++
        }
        while (newIdx < newChars.size) {
            pendingInsertions.append(newChars[newIdx])
            newIdx++
        }
        flushGroups(pendingDeletes, pendingInsertions)

        return "COMPACT_CHAR_DIFF\n" + diffGroups.joinToString("\n")
    }

    /**
     * Applies a diff format patch to a base text.
     */
    fun applyPatch(baseText: String, patchText: String): String {
        if (patchText.isBlank()) {
            return baseText
        }

        if (patchText.startsWith("COMPACT_CHAR_DIFF\n") || patchText.startsWith("CHAR_DIFF\n")) {
            return applyCompactCharPatch(baseText, patchText.substringAfter("\n"))
        }

        val baseLines = baseText.split('\n')
        val patchLines = patchText.lines()

        var baseIdx = 0
        val resultLines = mutableListOf<String>()

        for (patchLine in patchLines) {
            when {
                patchLine.startsWith("+ ") -> {
                    resultLines.add(patchLine.substring(2))
                }
                patchLine.startsWith("- ") -> {
                    val expectedLine = patchLine.substring(2)
                    if (baseIdx < baseLines.size && baseLines[baseIdx] == expectedLine) {
                        baseIdx++
                    } else {
                        throw IllegalArgumentException("Patch mismatch (line delete): expected '$expectedLine' but got '${baseLines.getOrNull(baseIdx)}'")
                    }
                }
                patchLine.startsWith("  ") -> {
                    val contextLine = patchLine.substring(2)
                    if (baseIdx < baseLines.size && baseLines[baseIdx] == contextLine) {
                        resultLines.add(contextLine)
                        baseIdx++
                    } else {
                        throw IllegalArgumentException("Patch mismatch (context): expected '$contextLine' but got '${baseLines.getOrNull(baseIdx)}'")
                    }
                }
            }
        }

        while (baseIdx < baseLines.size) {
            resultLines.add(baseLines[baseIdx])
            baseIdx++
        }

        return resultLines.joinToString("\n")
    }

    private fun applyCompactCharPatch(baseText: String, patchText: String): String {
        val baseChars = baseText.map { it.toString() }
        val patchLines = patchText.lines()
        
        var baseIdx = 0
        val resultChars = mutableListOf<String>()
        
        for (patchLine in patchLines) {
            if (patchLine.isEmpty()) continue
            when {
                patchLine.startsWith("+ ") -> {
                    // Added a group of characters
                    resultChars.add(patchLine.substring(2))
                }
                patchLine.startsWith("- ") -> {
                    // Deleted a group of characters
                    val deletedStr = patchLine.substring(2)
                    val expectedPart = baseText.substring(baseIdx).take(deletedStr.length)
                    if (expectedPart == deletedStr) {
                        baseIdx += deletedStr.length
                    } else {
                        throw IllegalArgumentException("Patch mismatch (group delete): expected '$deletedStr' at index $baseIdx")
                    }
                }
                patchLine.startsWith("  ") -> {
                    // Context character
                    val contextChar = patchLine.substring(2)
                    if (baseIdx < baseChars.size && baseChars[baseIdx] == contextChar) {
                        resultChars.add(contextChar)
                        baseIdx++
                    } else {
                        throw IllegalArgumentException("Patch mismatch (char context): expected '$contextChar' at index $baseIdx")
                    }
                }
            }
        }
        while (baseIdx < baseChars.size) {
            resultChars.add(baseChars[baseIdx])
            baseIdx++
        }
        return resultChars.joinToString("")
    }

    private fun <T> computeLongestCommonSubsequence(
        oldItems: List<T>,
        newItems: List<T>
    ): List<T> {
        val m = oldItems.size
        val n = newItems.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                if (oldItems[i - 1] == newItems[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val lcs = mutableListOf<T>()
        var i = m
        var j = n

        while (i > 0 && j > 0) {
            when {
                oldItems[i - 1] == newItems[j - 1] -> {
                    lcs.add(0, oldItems[i - 1])
                    i--
                    j--
                }
                dp[i - 1][j] > dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return lcs
    }
}
