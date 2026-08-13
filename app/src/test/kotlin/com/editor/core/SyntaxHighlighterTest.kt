package com.editor.core

import com.editor.core.features.editor.ui.helper.SyntaxHighlighter
import com.editor.core.features.editor.ui.helper.SyntaxMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SyntaxHighlighterTest {

    @Test
    fun testModeAutoDetection() {
        assertEquals(SyntaxMode.KOTLIN, SyntaxHighlighter.detectMode("Main.kt"))
        assertEquals(SyntaxMode.KOTLIN, SyntaxHighlighter.detectMode("build.gradle.kts"))
        assertEquals(SyntaxMode.MARKDOWN, SyntaxHighlighter.detectMode("README.md"))
        assertEquals(SyntaxMode.MARKDOWN, SyntaxHighlighter.detectMode("doc.markdown"))
        assertEquals(SyntaxMode.PLAIN_TEXT, SyntaxHighlighter.detectMode("notes.txt"))
        assertEquals(SyntaxMode.PLAIN_TEXT, SyntaxHighlighter.detectMode("unknown_file"))
        assertEquals(SyntaxMode.PLAIN_TEXT, SyntaxHighlighter.detectMode(null))
    }
}
