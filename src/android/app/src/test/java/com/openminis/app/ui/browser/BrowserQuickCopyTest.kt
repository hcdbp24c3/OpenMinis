package com.openminis.app.ui.browser

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-browser-quick-copy] The browser's copy button copies the page link. */
class BrowserQuickCopyTest {

    @Test
    fun nothingToCopyOnAnEmptyTab() {
        assertFalse(BrowserQuickCopy.canCopy(null))
        assertFalse(BrowserQuickCopy.canCopy(""))
        assertFalse(BrowserQuickCopy.canCopy("   "))
        assertFalse(BrowserQuickCopy.canCopy("about:blank"))
        assertTrue(BrowserQuickCopy.canCopy("https://example.com/a?b=1"))
        assertNull(BrowserQuickCopy.payload(BrowserQuickCopy.Kind.LINK, "", "Title"))
    }

    @Test
    fun linkIsTheUrlUnchanged() {
        assertEquals(
            "https://example.com/a?b=1#c",
            BrowserQuickCopy.payload(BrowserQuickCopy.Kind.LINK, " https://example.com/a?b=1#c ", "Title"),
        )
    }

    @Test
    fun markdownLinkEscapesBracketsAndParentheses() {
        assertEquals(
            "[A \\[draft\\] page](https://example.com/x_%28y%29)",
            BrowserQuickCopy.payload(
                BrowserQuickCopy.Kind.MARKDOWN_LINK,
                "https://example.com/x_(y)",
                "A [draft] page",
            ),
        )
        assertEquals(
            "https://example.com/",
            BrowserQuickCopy.payload(BrowserQuickCopy.Kind.MARKDOWN_LINK, "https://example.com/", "  "),
        )
    }

    @Test
    fun textIsCappedAndBlankTextIsNothing() {
        assertNull(BrowserQuickCopy.payload(BrowserQuickCopy.Kind.TEXT, "https://example.com/", "t", "  "))
        val big = "x".repeat(BrowserQuickCopy.MAX_CHARS + 50)
        val out: String? = BrowserQuickCopy.payload(BrowserQuickCopy.Kind.TEXT, null, null, big)
        assertEquals(BrowserQuickCopy.MAX_CHARS, out?.length)
    }

    @Test
    fun theToolbarButtonCopiesTheLink() {
        val src: String = ProductionSources.read("ui/browser/BrowserSheet.kt")
        assertTrue(src.contains("BrowserQuickCopy.Kind.LINK"))
        assertTrue(src.contains("enabled = BrowserQuickCopy.canCopy(currentURL)"))
    }
}
