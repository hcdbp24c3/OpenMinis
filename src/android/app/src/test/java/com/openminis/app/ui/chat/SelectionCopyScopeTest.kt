package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copying a PART of one message must never hand back the whole message.
 *
 * Reported from a real reply — a long test report with tables — where selecting from
 * "Test xong. Kết quả:" to the end and tapping Copy produced the reply from its top.
 *
 * The selection itself is exact: the per-shard walk slices both endpoints. What can
 * widen it is [SelectionController.spliceNonShardSpan], which prefers a slice of the
 * cached MARKDOWN when the selection crosses a table or a code fence (those blocks
 * render through their own composables and never register as text shards, so the walk
 * alone would drop them).
 *
 * That slice is anchored by short substrings of the selected text — and the anchors
 * were located with a plain `indexOf`, i.e. at their FIRST occurrence in the message.
 * "Kết quả:" is a phrase a report repeats ("Kết quả: search OK." near the top, the
 * same words inside the selection lower down), so the slice started at the earlier
 * look-alike and ran to the end: the copy silently widened to most of the reply. The
 * splice is only allowed to ADD non-shard content to the exact walk, never to move
 * where the selection begins or ends.
 */
class SelectionCopyScopeTest {

    private val msg = "M1"
    private fun shard(index: Int, sub: Int = 0) = TextShardId(msg, "mdblock:p1:$index#$sub")

    /**
     * The message, with the two features that matter: a table inside the selected
     * span (non-shard → the splice path) and "Kết quả:" appearing BEFORE the
     * selection (the anchor ambiguity).
     */
    private val markdown = """
        Test luôn cho bạn.

        Search chạy tốt.

        Kết quả: search OK.

        | Đường | Kết quả |
        |---|---|
        | fetch | ❌ EOF |

        Test xong. Kết quả:

        web_fetch — nửa sống nửa chết
    """.trimIndent()

    private val s0 = shard(0)
    private val s1 = shard(1)
    private val s2 = shard(2)
    private val s3 = shard(3)
    private val s4 = shard(4)

    /** Rendered shard text — note the table contributes NO shard (by design). */
    private val text = mapOf(
        s0 to "Test luôn cho bạn.",
        s1 to "Search chạy tốt.",
        s2 to "Kết quả: search OK.",
        s3 to "Test xong. Kết quả:",
        s4 to "web_fetch — nửa sống nửa chết",
    )
    private val y = mapOf(s0 to 0f, s1 to 50f, s2 to 100f, s3 to 150f, s4 to 200f)

    private fun controller() = SelectionController().apply {
        for (id in listOf(s0, s1, s2, s3, s4)) {
            registerText(ShardText(id, text.getValue(id), null) { y.getValue(id) })
        }
        rememberMessageMarkdown(msg, markdown)
    }

    // ── a message-level Copy: selection, else the ANSWER, else the turn ─────
    //
    // Reported twice. First: an agent turn renders `a` · tool call · tool call · `b`,
    // pressing Copy under the reply put BOTH `a` and `b` on the clipboard, so the copy
    // action now takes the closing text block — the answer — and leaves the narration
    // behind. Second: with a selection active it took everything anyway, because the
    // label was computed by EXTRACTING the selected text for every row on every frame
    // (which is also what made a long chat janky); the label now asks a cheap question
    // and the text is only extracted when the button is pressed.

    @Test
    fun `copy under a reply takes the closing block, not the whole turn`() {
        val ctl = controller()
        val finalText = "web_fetch — nửa sống nửa chết"
        val turnText = "Test luôn cho bạn.\nKết quả: search OK.\n$finalText"

        // Nothing selected: the answer, not the turn it was narrated in.
        assertEquals(finalText, ctl.copyScopeText(msg, finalText, turnText))
        // The label asks the cheap question, and it is false here.
        assertFalse(ctl.hasSelectionIn(msg))
    }

    @Test
    fun `copy under a reply takes the selected range when one exists`() {
        val ctl = controller()
        val finalText = "web_fetch — nửa sống nửa chết"
        val turnText = "Test luôn cho bạn.\n$finalText"

        ctl.beginSelection(TextPosition(s4, 0))
        ctl.replaceEnd(TextPosition(s4, text.getValue(s4).length))

        // The selection outranks the answer and the turn.
        assertEquals(finalText, ctl.copyScopeText(msg, finalText, turnText))
        assertTrue(ctl.hasSelectionIn(msg))
    }

    @Test
    fun `a turn with no text block falls back to the whole turn`() {
        // A tool-only turn (or a legacy row) has nothing to prefer: the fallback keeps
        // the action from doing nothing at all.
        val ctl = controller()
        assertEquals("the turn", ctl.copyScopeText(msg, "", "the turn"))
    }

    @Test
    fun `a selection in another message does not leak into this reply's copy`() {
        val ctl = controller()
        ctl.beginSelection(TextPosition(s4, 0))
        ctl.replaceEnd(TextPosition(s4, text.getValue(s4).length))
        assertEquals("answer", ctl.copyScopeText("OTHER", "answer", "the turn"))
        assertFalse(ctl.hasSelectionIn("OTHER"))
    }

    @Test
    fun `a collapsed selection is not treated as a selection`() {
        val ctl = controller()
        // A caret (or a cleared selection) must fall back to the answer: an empty
        // clipboard is worse than a wider one.
        ctl.beginSelection(TextPosition(s1, 2))
        ctl.replaceEnd(TextPosition(s1, 2))
        assertFalse(ctl.hasSelectionIn(msg))
        assertEquals("answer", ctl.copyScopeText(msg, "answer", "turn"))
    }

    // ── the reported case ───────────────────────────────────────────────────

    @Test
    fun `a selection that starts on a repeated phrase copies only the selection`() {
        val ctl = controller()
        // From "Kết quả:" inside the LAST paragraph to the end of the reply — the
        // tail-first drag a user does when copying one section of a long answer.
        ctl.beginSelection(TextPosition(s3, "Test xong. ".length))
        ctl.replaceEnd(TextPosition(s4, text.getValue(s4).length))

        val copied = ctl.selectedPlainText()

        assertEquals("Kết quả:\nweb_fetch — nửa sống nửa chết", copied)
        assertFalse("the earlier 'Kết quả: search OK.' line must not be included", copied.contains("search OK"))
        assertFalse("the table above the selection must not be included", copied.contains("fetch | ❌ EOF"))
        assertFalse("the head of the reply must not be included", copied.contains("Test luôn cho bạn"))
    }

    @Test
    fun `a selection that crosses a table keeps the table but not the text before it`() {
        val ctl = controller()
        // Start on the paragraph ABOVE the table, end below it: the splice is what
        // recovers the table (`| Đường | Kết quả |` …) that has no shard. Markdown
        // whitespace (blank lines around the table) is preserved verbatim, so this
        // pins the BOUNDARIES rather than an exact string.
        ctl.beginSelection(TextPosition(s2, 0))
        ctl.replaceEnd(TextPosition(s4, text.getValue(s4).length))

        val copied = ctl.selectedPlainText()

        assertTrue(copied, copied.startsWith("Kết quả: search OK."))
        assertTrue(copied, copied.trimEnd().endsWith("web_fetch — nửa sống nửa chết"))
        assertTrue("the table has no shard, so only the splice can carry it", copied.contains("|---|---|"))
        assertFalse("the paragraph before the selection must not be included", copied.contains("Search chạy tốt"))
        assertFalse("the head of the reply must not be included", copied.contains("Test luôn cho bạn"))
    }

    @Test
    fun `a selection inside one paragraph is an exact substring`() {
        val ctl = controller()
        ctl.beginSelection(TextPosition(s1, 0))
        ctl.replaceEnd(TextPosition(s1, "Search".length))
        assertEquals("Search", ctl.selectedPlainText())
    }
}
