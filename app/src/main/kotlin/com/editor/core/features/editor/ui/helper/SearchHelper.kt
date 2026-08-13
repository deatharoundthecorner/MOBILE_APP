package com.editor.core.features.editor.ui.helper

import android.text.Editable
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import java.util.regex.PatternSyntaxException

/**
 * Data class representing a text match range.
 */
data class MatchRange(val start: Int, val end: Int)

/**
 * Helper class for managing search, navigation, replace, and match highlighting operations.
 * Isolates search algorithms and span styling from Activity lifecycle.
 */
class SearchHelper {

    var isCaseSensitive: Boolean = false
    var isWholeWord: Boolean = false
    var isRegex: Boolean = false

    private val matches = mutableListOf<MatchRange>()
    var currentIndex: Int = -1
        private set

    var lastQuery: String = ""
        private set

    // Highlight color ARGB values (pure integers to support JVM unit testing without Android stubs)
    private val colorAllMatches = 0x80FFE082.toInt() // Amber translucency
    private val colorActiveMatch = 0xFFFFB74D.toInt() // Deeper Orange

    val matchCount: Int
        get() = matches.size

    val activeMatchNumber: Int
        get() = if (currentIndex in matches.indices) currentIndex + 1 else 0

    fun getMatches(): List<MatchRange> = matches.toList()

    fun getCurrentMatch(): MatchRange? {
        return if (currentIndex in matches.indices) matches[currentIndex] else null
    }

    /**
     * Finds all matches for [query] in [text] applying configured options.
     * Returns true if search compiled and executed successfully.
     */
    fun search(text: String, query: String): Boolean {
        lastQuery = query
        matches.clear()
        currentIndex = -1

        if (query.isEmpty() || text.isEmpty()) {
            return true
        }

        try {
            if (isRegex) {
                val options = if (isCaseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val pattern = query.toRegex(options)
                pattern.findAll(text).forEach { matchResult ->
                    val start = matchResult.range.first
                    val end = matchResult.range.last + 1
                    if (!isWholeWord || isWordBoundary(text, start, end)) {
                        matches.add(MatchRange(start, end))
                    }
                }
            } else {
                var start = 0
                while (start < text.length) {
                    val index = text.indexOf(query, start, ignoreCase = !isCaseSensitive)
                    if (index == -1) break
                    val end = index + query.length
                    if (!isWholeWord || isWordBoundary(text, index, end)) {
                        matches.add(MatchRange(index, end))
                    }
                    start = index + 1
                }
            }

            if (matches.isNotEmpty()) {
                currentIndex = 0
            }
            return true
        } catch (e: PatternSyntaxException) {
            return false
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Navigates to next or previous match, returning the target [MatchRange].
     */
    fun navigate(forward: Boolean): MatchRange? {
        if (matches.isEmpty()) return null

        currentIndex = if (forward) {
            (currentIndex + 1) % matches.size
        } else {
            if (currentIndex <= 0) matches.size - 1 else currentIndex - 1
        }
        return matches[currentIndex]
    }

    /**
     * Applies match highlighting spans to the given editable buffer.
     */
    fun applyHighlights(editable: Editable) {
        clearHighlights(editable)

        if (matches.isEmpty()) return

        for (i in matches.indices) {
            val match = matches[i]
            if (match.start >= 0 && match.end <= editable.length) {
                val color = if (i == currentIndex) colorActiveMatch else colorAllMatches
                editable.setSpan(
                    BackgroundColorSpan(color),
                    match.start,
                    match.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /**
     * Removes all search highlight spans from editable buffer.
     */
    fun clearHighlights(editable: Editable) {
        val spans = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        for (span in spans) {
            val color = span.backgroundColor
            if (color == colorAllMatches || color == colorActiveMatch) {
                editable.removeSpan(span)
            }
        }
    }

    /**
     * Replaces current match in [editable] with [replacement].
     * Returns true if replacement succeeded.
     */
    fun replaceCurrent(editable: Editable, replacement: String): Boolean {
        val match = getCurrentMatch() ?: return false
        if (match.start < 0 || match.end > editable.length) return false

        val replacementText = getReplacementString(
            text = editable.substring(match.start, match.end),
            replacement = replacement
        )

        editable.replace(match.start, match.end, replacementText)
        return true
    }

    /**
     * Replaces all matches in [text] with [replacement].
     * Returns the modified string and total count of replacements.
     */
    fun replaceAll(text: String, replacement: String): Pair<String, Int> {
        if (matches.isEmpty()) return Pair(text, 0)

        val sb = StringBuilder(text)
        var count = 0
        // Iterate backwards so indices remain valid during replacement
        for (i in matches.indices.reversed()) {
            val match = matches[i]
            if (match.start >= 0 && match.end <= sb.length) {
                val matchedSubstring = sb.substring(match.start, match.end)
                val replacementText = getReplacementString(matchedSubstring, replacement)
                sb.replace(match.start, match.end, replacementText)
                count++
            }
        }

        return Pair(sb.toString(), count)
    }

    private fun getReplacementString(text: String, replacement: String): String {
        if (!isRegex) return replacement
        return try {
            val options = if (isCaseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val regex = lastQuery.toRegex(options)
            regex.replaceFirst(text, replacement)
        } catch (e: Exception) {
            replacement
        }
    }

    private fun isWordBoundary(text: String, start: Int, end: Int): Boolean {
        val charBefore = if (start > 0) text[start - 1] else ' '
        val charAfter = if (end < text.length) text[end] else ' '

        fun isWordPart(c: Char) = c.isLetterOrDigit() || c == '_'

        return !isWordPart(charBefore) && !isWordPart(charAfter)
    }

    fun getStatusText(): String {
        return when {
            lastQuery.isEmpty() -> ""
            matches.isEmpty() -> "No matches"
            else -> "$activeMatchNumber of ${matches.size}"
        }
    }
}
