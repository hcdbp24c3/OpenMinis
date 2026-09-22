package com.openminis.app.ui.settings

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure search filter used by ManageProviderModelsSheet. Top-level and
 * side-effect free so the UI can call it from remember without re-filtering on
 * every recomposition. Unlike the old collapsed provider list, the manage sheet
 * shows the FULL catalog — a blank query returns everything uncapped.
 */
class ModelListLogicTest {

    @Test
    fun `blank query returns all entries uncapped`() {
        val entries = List(2000) { makeEntry("m$it") }
        val shown = filterManageModels(entries, "   ")
        assertEquals(2000, shown.size)
        assertEquals("m0", shown.first().model.id) // order preserved
    }

    @Test
    fun `filters by name or id ignoring case`() {
        val entries = listOf(makeEntry("gpt-4"), makeEntry("gpt-4-turbo"), makeEntry("claude"))
        val hit = filterManageModels(entries, "GPT-4")
        assertEquals(listOf("gpt-4", "gpt-4-turbo"), hit.map { it.model.id })
    }

    @Test
    fun `matches model id even when displayName differs`() {
        val entries = listOf(makeEntry("deepseek-chat", displayName = "DeepSeek"))
        assertTrue(filterManageModels(entries, "deepseek").isNotEmpty())
    }

    @Test
    fun `matches display name case-insensitively`() {
        val entries = listOf(makeEntry("gpt-4o-mini", displayName = "GPT-4o Mini"))
        val hit = filterManageModels(entries, "gpt-4o mini")
        assertEquals(listOf("gpt-4o-mini"), hit.map { it.model.id })
    }

    @Test
    fun `no match returns empty list`() {
        val entries = listOf(makeEntry("gpt-4"))
        assertTrue(filterManageModels(entries, "claude").isEmpty())
    }

    private fun makeEntry(id: String, displayName: String = id): ModelEntry =
        ModelEntry(
            providerInstanceId = "provider",
            baseModel = LLMModel(id = id, displayName = displayName, provider = "test"),
        )
}
