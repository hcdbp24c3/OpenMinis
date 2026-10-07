package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-usage-capsule-style] The token-usage footer is VISIBLE by default.
 *
 * `ctx:47.0k in:818 out:735 cache:46.2k 11:55` is the number that tells you whether the
 * next turn is anywhere near the context limit, and it used to be an easter egg: hidden
 * until the user found the blank strip under a reply and tapped it. The set in the
 * ViewModel now records the messages a tap has put AWAY, so the default is visible.
 *
 * The rule lives in three places that can silently disagree — the ViewModel's flow, and
 * the two renderers (classic chat + the helper transcript) — and the failure mode is
 * invisible in a screenshot: a re-inverted condition simply hides the row again, which is
 * exactly what it looked like before. So this pins the wiring rather than the pixels.
 */
class UsageCapsuleVisibilityTest {

    private fun source(rel: String) = ProductionSources.read(rel)

    @Test
    fun `the ViewModel records hidden messages, not revealed ones`() {
        val vm = source("ui/chat/ChatViewModel.kt")
        assertTrue("the flow must exist", vm.contains("val hiddenUsageIds: StateFlow<Set<String>>"))
        assertTrue("the set records dismissals", vm.contains("internal val _hiddenUsageIds"))
        assertFalse(
            "revealedUsageIds would mean hidden by default again",
            vm.contains("revealedUsageIds"),
        )
    }

    @Test
    fun `both renderers show the capsule unless the message was dismissed`() {
        for (rel in listOf("ui/chat/ChatScreen.kt", "ui/chat/HelperUi.kt")) {
            val src = source(rel)
            assertTrue("$rel must read the hidden set", src.contains("hiddenUsageIds"))
            assertTrue(
                "$rel must show the capsule when the message is not in it",
                src.contains("msgId !in hiddenUsage") || src.contains("msgId !in hiddenUsageIds"),
            )
            assertFalse("$rel must not gate on a revealed set", src.contains("in revealedUsage"))
        }
    }

    @Test
    fun `the copy-all action is a text chip, not a second identical copy glyph`() {
        // Two ContentCopy glyphs side by side under a reply were indistinguishable; the
        // second scope is spelled out instead of drawn a second time.
        val src = source("ui/chat/ChatScreen.kt")
        assertTrue("the chip label must be used", src.contains("labelText = stringResource(R.string.chat_copy_all_short)"))
        val row = source("ui/chat/ChatMiscViews.kt")
        assertTrue("the row must render chip text", row.contains("fontFamily = FontFamily.Monospace"))
    }
}
