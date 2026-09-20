package com.openminis.app.ui.settings

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-list-search] Pins the pure search/collapse logic that keeps
 * a ~2000-model provider list bounded and searchable. The helpers are
 * top-level and side-effect free so the UI can call them from
 * remember/derivedStateOf without re-sorting on every recomposition.
 */
class ModelListLogicTest {

    @Test
    fun `uncollapsed cap keeps list bounded when no search`() {
        val entries = List(2000) { makeEntry("m$it") }
        val shown = collapseEntries(entries, maxShown = 20)
        assertEquals(20, shown.size)
        assertEquals("m0", shown.first().model.id) // rank order preserved
    }

    @Test
    fun `search filters by name or id ignoring case`() {
        val entries = listOf(makeEntry("gpt-4"), makeEntry("gpt-4-turbo"), makeEntry("claude"))
        val hit = filterModelsForSearch(entries, "gpt-4", Int.MAX_VALUE)
        assertEquals(listOf("gpt-4", "gpt-4-turbo"), hit.map { it.model.id })
    }

    @Test
    fun `search matches model id even when displayName differs`() {
        val entries = listOf(makeEntry("deepseek-chat", displayName = "DeepSeek"))
        assertTrue(filterModelsForSearch(entries, "deepseek", 50).isNotEmpty())
    }

    @Test
    fun `search matches display name case-insensitively`() {
        val entries = listOf(makeEntry("gpt-4o-mini", displayName = "GPT-4o Mini"))
        val hit = filterModelsForSearch(entries, "gpt-4o mini", Int.MAX_VALUE)
        assertEquals(listOf("gpt-4o-mini"), hit.map { it.model.id })
    }

    @Test
    fun `blank query collapses instead of filtering`() {
        val entries = List(100) { makeEntry("m$it") }
        val hit = filterModelsForSearch(entries, "   ", maxShown = 5)
        assertEquals(5, hit.size)
    }

    private fun makeEntry(id: String, displayName: String = id): ModelEntry =
        ModelEntry(
            providerInstanceId = "provider",
            baseModel = LLMModel(id = id, displayName = displayName, provider = "test"),
        )
}