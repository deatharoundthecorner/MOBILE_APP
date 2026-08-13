package com.editor.core

import com.editor.core.features.editor.ui.helper.SearchHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchHelperTest {

    private lateinit var helper: SearchHelper

    @Before
    fun setUp() {
        helper = SearchHelper()
    }

    @Test
    fun testCaseInsensitiveSearch() {
        helper.isCaseSensitive = false
        val text = "Hello World hello WORLD"
        val success = helper.search(text, "hello")

        assertTrue(success)
        assertEquals(2, helper.matchCount)
        assertEquals(1, helper.activeMatchNumber)
        assertEquals("1 of 2", helper.getStatusText())
    }

    @Test
    fun testCaseSensitiveSearch() {
        helper.isCaseSensitive = true
        val text = "Hello World hello WORLD"
        val success = helper.search(text, "hello")

        assertTrue(success)
        assertEquals(1, helper.matchCount)
        assertEquals(12, helper.getMatches()[0].start)
    }

    @Test
    fun testWholeWordSearch() {
        helper.isWholeWord = true
        val text = "val value = val_var + val"
        val success = helper.search(text, "val")

        assertTrue(success)
        // Should match "val" at index 0 and "val" at index 22, but NOT "value" or "val_var"
        assertEquals(2, helper.matchCount)
        assertEquals(0, helper.getMatches()[0].start)
        assertEquals(22, helper.getMatches()[1].start)
    }

    @Test
    fun testRegexSearch() {
        helper.isRegex = true
        val text = "Item 1, Item 42, Item 100"
        val success = helper.search(text, "\\d+")

        assertTrue(success)
        assertEquals(3, helper.matchCount)
        assertEquals(5, helper.getMatches()[0].start) // "1"
        assertEquals(13, helper.getMatches()[1].start) // "42"
    }

    @Test
    fun testInvalidRegexHandledGracefully() {
        helper.isRegex = true
        val text = "Some content"
        val success = helper.search(text, "[invalid(regex")

        assertFalse(success)
        assertEquals(0, helper.matchCount)
    }

    @Test
    fun testNavigationWrapping() {
        helper.search("one two one three one", "one")
        assertEquals(3, helper.matchCount)
        assertEquals(1, helper.activeMatchNumber) // Index 0 ->Match 1

        val match2 = helper.navigate(forward = true)
        assertNotNull(match2)
        assertEquals(2, helper.activeMatchNumber)

        val match3 = helper.navigate(forward = true)
        assertNotNull(match3)
        assertEquals(3, helper.activeMatchNumber)

        // Wraps around to match 1
        val match1Wrapped = helper.navigate(forward = true)
        assertNotNull(match1Wrapped)
        assertEquals(1, helper.activeMatchNumber)

        // Navigate backward wraps to match 3
        val match3Prev = helper.navigate(forward = false)
        assertNotNull(match3Prev)
        assertEquals(3, helper.activeMatchNumber)
    }

    @Test
    fun testReplaceAllLiteral() {
        val text = "foo bar foo baz FOO"
        helper.isCaseSensitive = false
        helper.search(text, "foo")

        val (resultText, count) = helper.replaceAll(text, "qux")
        assertEquals(3, count)
        assertEquals("qux bar qux baz qux", resultText)
    }

    @Test
    fun testReplaceAllRegex() {
        helper.isRegex = true
        val text = "user1 user2 user3"
        helper.search(text, "user(\\d+)")

        val (resultText, count) = helper.replaceAll(text, "client_$1")
        assertEquals(3, count)
        assertEquals("client_1 client_2 client_3", resultText)
    }
}
