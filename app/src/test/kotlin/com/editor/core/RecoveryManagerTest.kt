package com.editor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryManagerTest {

    @Test
    fun testCacheBufferParsing() {
        val path = "file:///storage/emulated/0/document.txt"
        val textContent = "First line of unsaved changes\nSecond line of unsaved changes"

        val rawSaved = "$path\n$textContent"
        val lines = rawSaved.lines()

        assertEquals(path, lines[0])
        val reconstructedContent = lines.drop(1).joinToString("\n")
        assertEquals(textContent, reconstructedContent)
        assertTrue(reconstructedContent.contains("First line"))
    }
}
