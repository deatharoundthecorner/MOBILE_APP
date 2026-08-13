package com.editor.core.features.editor.ui.helper

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

enum class SyntaxMode {
    PLAIN_TEXT,
    KOTLIN,
    MARKDOWN
}

/**
 * Helper class managing syntax highlighting for Kotlin, Markdown, and Plain Text.
 * Applies character spans non-destructively to editable text buffers.
 */
class SyntaxHighlighter {

    private val kotlinKeywordRegex = Regex(
        "\\b(fun|val|var|class|object|interface|sealed|enum|if|else|for|while|return|import|package|when|data|private|public|protected|internal|override|try|catch|finally|throw|is|as|true|false|null|this|super)\\b"
    )
    private val kotlinStringRegex = Regex("\".*?\"|\"\"\"[\\s\\S]*?\"\"\"")
    private val kotlinCommentRegex = Regex("//.*|/\\*[\\s\\S]*?\\*/")

    private val markdownHeadingRegex = Regex("(?m)^#+.*")
    private val markdownBoldRegex = Regex("\\*\\*.*?\\*\\*")
    private val markdownCodeRegex = Regex("`[^`\\n]+`")
    private val markdownListRegex = Regex("(?m)^[ \\t]*([-*]|\\d+\\.)[ \\t]+.*")

    companion object {
        fun detectMode(fileNameOrPath: String?): SyntaxMode {
            if (fileNameOrPath.isNullOrBlank()) return SyntaxMode.PLAIN_TEXT
            val lower = fileNameOrPath.lowercase()
            return when {
                lower.endsWith(".kt") || lower.endsWith(".kts") -> SyntaxMode.KOTLIN
                lower.endsWith(".md") || lower.endsWith(".markdown") -> SyntaxMode.MARKDOWN
                else -> SyntaxMode.PLAIN_TEXT
            }
        }
    }

    /**
     * Clears previous syntax spans and applies new syntax styling for [mode].
     */
    fun applySyntaxHighlighting(editable: Editable, mode: SyntaxMode) {
        clearSyntaxSpans(editable)

        if (mode == SyntaxMode.PLAIN_TEXT || editable.isEmpty()) {
            return
        }

        when (mode) {
            SyntaxMode.KOTLIN -> applyKotlin(editable)
            SyntaxMode.MARKDOWN -> applyMarkdown(editable)
            SyntaxMode.PLAIN_TEXT -> {}
        }
    }

    /**
     * Removes syntax spans while preserving selection and search highlight spans.
     */
    fun clearSyntaxSpans(editable: Editable) {
        val spans = editable.getSpans(0, editable.length, CharacterStyle::class.java)
        for (span in spans) {
            if (span is ForegroundColorSpan) {
                // Do not remove search highlight spans
                val color = span.foregroundColor
                if (color != Color.parseColor("#80FFE082") && color != Color.parseColor("#FFB74D")) {
                    editable.removeSpan(span)
                }
            } else if (span is StyleSpan || span is TypefaceSpan) {
                editable.removeSpan(span)
            }
        }
    }

    private fun applyKotlin(editable: Editable) {
        val textStr = editable.toString()

        // 1. Keywords (Blue Bold)
        kotlinKeywordRegex.findAll(textStr).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            editable.setSpan(
                ForegroundColorSpan(Color.parseColor("#2196F3")),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                StyleSpan(Typeface.BOLD),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 2. Strings (Green)
        kotlinStringRegex.findAll(textStr).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            editable.setSpan(
                ForegroundColorSpan(Color.parseColor("#4CAF50")),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 3. Comments (Gray Italic)
        kotlinCommentRegex.findAll(textStr).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            editable.setSpan(
                ForegroundColorSpan(Color.parseColor("#9E9E9E")),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                StyleSpan(Typeface.ITALIC),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyMarkdown(editable: Editable) {
        val textStr = editable.toString()

        // 1. Headings (Purple Bold)
        markdownHeadingRegex.findAll(textStr).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            editable.setSpan(
                ForegroundColorSpan(Color.parseColor("#9C27B0")),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                StyleSpan(Typeface.BOLD),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 2. Bold (Bold)
        markdownBoldRegex.findAll(textStr).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            editable.setSpan(
                StyleSpan(Typeface.BOLD),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 3. Inline Code (Pink Monospace)
        markdownCodeRegex.findAll(textStr).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            editable.setSpan(
                TypefaceSpan("monospace"),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                ForegroundColorSpan(Color.parseColor("#E91E63")),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 4. Lists (Orange)
        markdownListRegex.findAll(textStr).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            editable.setSpan(
                ForegroundColorSpan(Color.parseColor("#FF9800")),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
