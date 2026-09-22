package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-picker-debounce] Pins the pure picker logic behind the
 * chat/agent model picker perf work: the release-rank re-sort is skipped while
 * a search is active (so ~2000 entries are not re-sorted on every keystroke),
 * and the search query is debounced before it reaches the filter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerLogicTest {

    @Test
    fun `default list is release-ranked newest first`() {
        val entries = listOf(makeEntry("gpt-3.5"), makeEntry("gpt-4"), makeEntry("claude-3"))
        val ranked = Comparator<ModelEntry> { a, b -> b.model.id.compareTo(a.model.id) }
        val out = orderPickerEntries(entries, searching = false, releaseRank = ranked)
        assertEquals(listOf("gpt-4", "gpt-3.5", "claude-3"), out.map { it.model.id })
    }

    @Test
    fun `search skips the release-rank re-sort`() {
        val entries = listOf(makeEntry("gpt-3.5"), makeEntry("gpt-4"))
        val ranked = Comparator<ModelEntry> { a, b -> b.model.id.compareTo(a.model.id) }
        val out = orderPickerEntries(entries, searching = true, releaseRank = ranked)
        // Same list instance: no copy, no sort — the ~2000-entry filter runs
        // without an O(n log n) re-sort on every keystroke.
        assertSame(entries, out)
        assertEquals(listOf("gpt-3.5", "gpt-4"), out.map { it.model.id })
    }

    @Test
    fun `debounce waits for quiet before returning the query`() = runTest {
        var result: String? = null
        val job = launch { result = debounceSearchText("gpt", debounceMillis = 250) }
        advanceTimeBy(249)
        assertNull(result)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("gpt", result)
        job.cancel()
    }

    @Test
    fun `matches contiguous substring case-insensitively`() {
        assertTrue(matchesModelQuery("DeepSeek-V4-Flash", "deepseek-v4-flash"))
        assertTrue(matchesModelQuery("deepseek-flash", "DEEPSEEK-FLASH"))
        assertTrue(matchesModelQuery("gpt-4o-mini", "gpt-4o"))
    }

    @Test
    fun `does not match subsequence across interruptions`() {
        // Kelivo precision: chars-in-order fuzzy must not fire.
        assertFalse(matchesModelQuery("deepseek-v4-flash", "deepseek-flash"))
        assertFalse(matchesModelQuery("deepseek-v4-flash", "dseek"))
        assertFalse(matchesModelQuery("claude-3-opus", "clopus"))
    }

    @Test
    fun `blank query matches everything`() {
        assertTrue(matchesModelQuery("any-model", ""))
        assertTrue(matchesModelQuery("any-model", "   "))
    }

    private fun makeEntry(id: String): ModelEntry =
        ModelEntry(
            providerInstanceId = "provider",
            baseModel = LLMModel(id = id, displayName = id, provider = "test"),
        )
}