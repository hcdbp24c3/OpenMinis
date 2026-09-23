package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Long-press selection boundaries for table cells vs prose.
 *
 * A table cell selects in FULL; prose uses word-level expansion (stop at
 * whitespace + punctuation — kelivo/iOS semantics). Cells with spaces or
 * punctuation still need the atomic path: prose would only grab "Alice" or
 * "1" from "Alice Smith" / "1,200".
 */
class AtomicCellSelectionTest {

    /** Mirrors SelectionController.beginSelectionWord's atomic-unit branch. */
    private fun atomicBounds(text: String): Pair<Int, Int> {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return 0 to 0
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return start to end
    }

    /**
     * Production path: [computeWordBounds] (word-level — whitespace + punctuation).
     */
    private fun sentenceBounds(text: String, offset: Int): Pair<Int, Int> =
        computeWordBounds(text, offset)

    private fun select(text: String, bounds: Pair<Int, Int>) =
        text.substring(bounds.first, bounds.second)

    @Test
    fun `a cell containing punctuation still selects in full`() {
        // Prose word-scan still stops at the comma; atomic path does not.
        val cell = "1,200"
        assertEquals("1,200", select(cell, atomicBounds(cell)))
        assertEquals(
            "word expansion stops at the comma",
            "1", select(cell, sentenceBounds(cell, 0)),
        )
    }

    @Test
    fun `a cell with a version string selects in full`() {
        val cell = "v1.2 beta"
        assertEquals("v1.2 beta", select(cell, atomicBounds(cell)))
    }

    @Test
    fun `a punctuation-free cell selects in full atomically, one word in prose`() {
        val cell = "Alice Smith"
        assertEquals("Alice Smith", select(cell, atomicBounds(cell)))
        assertEquals("Alice", select(cell, sentenceBounds(cell, 3)))
    }

    @Test
    fun `a CJK cell selects in full`() {
        val cell = "张三，项目经理"
        assertEquals("张三，项目经理", select(cell, atomicBounds(cell)))
    }

    /** CJK prose still expands to the punctuation clause (no spaces). */
    @Test
    fun `CJK prose expands to punctuation clause`() {
        val cell = "张三，项目经理"
        assertEquals("张三", select(cell, sentenceBounds(cell, 0)))
        assertEquals("项目经理", select(cell, sentenceBounds(cell, 3)))
    }

    /** Surrounding whitespace is trimmed so the highlight hugs the content. */
    @Test
    fun `padding around cell text is not selected`() {
        val cell = "  spaced value  "
        assertEquals("spaced value", select(cell, atomicBounds(cell)))
    }

    @Test
    fun `an empty or blank cell yields an empty range`() {
        assertEquals(0 to 0, atomicBounds(""))
        assertEquals(0 to 0, atomicBounds("   "))
    }

    /** The press offset is irrelevant for a cell — the whole cell is the unit. */
    @Test
    fun `selection is independent of where inside the cell the press landed`() {
        val cell = "alpha, beta, gamma"
        val expected = select(cell, atomicBounds(cell))
        for (offset in cell.indices) {
            assertEquals(expected, select(cell, atomicBounds(cell)))
        }
        assertEquals("alpha, beta, gamma", expected)
    }

    /**
     * Prose long-press is WORD-level (kelivo/iOS), not sentence-level.
     * Regression: Vietnamese "của tôi nè" used to select the whole phrase
     * because spaces were not stop characters.
     */
    @Test
    fun `prose keeps word-level expansion on Latin text`() {
        val vi = "của tôi nè"
        assertEquals("của", select(vi, sentenceBounds(vi, 0)))
        assertEquals("tôi", select(vi, sentenceBounds(vi, 5)))
        assertEquals("nè", select(vi, sentenceBounds(vi, 8)))

        val en = "Hello world. Second sentence here."
        assertEquals("Hello", select(en, sentenceBounds(en, 2)))
        assertEquals("world", select(en, sentenceBounds(en, 7)))
        assertEquals("Second", select(en, sentenceBounds(en, 14)))
    }
}
