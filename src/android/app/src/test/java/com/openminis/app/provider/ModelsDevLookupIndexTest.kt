package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-modelsdev-lookup-index] `resolveDevEntry` reads a per-registry
 * index instead of sorting and normalizing the catalog for every model and
 * every alias candidate. The index must not change a single answer: the old
 * body is kept as `resolveDevEntryReference` and compared here.
 */
class ModelsDevLookupIndexTest {

    @After
    fun tearDown() = ModelsDevApi.setRegistryForTesting(null)

    private fun entry(id: String, ctx: Int, efforts: List<String>? = null) = ModelsDevApi.ModelDevEntry(
        id = id, name = null, family = null, contextWindow = ctx, maxOutputTokens = null,
        reasoning = efforts != null, interleavedField = null, inputModalities = null,
        outputModalities = null, reasoningEffortValues = efforts, declaresNoEffortTiers = false,
        releaseDate = null, outputCost = null,
    )

    private fun provider(key: String, vararg models: ModelsDevApi.ModelDevEntry) =
        key to ModelsDevApi.ProviderEntry(id = key, name = key, api = null, models = models.associateBy { it.id })

    /** Collisions on purpose: same normalized key inside one provider and across providers. */
    private val registry = mapOf(
        provider("zeta", entry("glm-5.3-flash", 1), entry("GLM-5.3-Flash", 2), entry("glm_5_3_flash", 3)),
        provider("alpha", entry("vendor/glm-5.3-flash", 4), entry("deepseek-flash", 5)),
        provider("mid", entry("glm-5.3-flash", 6, listOf("low", "high")), entry("deepseek/deepseek-flash", 7)),
        provider("openai", entry("gpt-x.1", 8, listOf("low")), entry("gpt-x-1", 9), entry("o9-mini", 10)),
        provider("beta", entry("model-x", 11), entry("model-x:free", 12), entry("Model_X", 13, listOf("max"))),
        provider("empty"),
    )

    private val ids = listOf(
        "glm-5.3-flash", "GLM-5.3-FLASH", "glm_5_3_flash", "vendor/glm-5.3-flash", "relay/GLM_5.3_Flash",
        "glm-5.3-flash-cpa", "glm-5.3-flash-cpa-x1", "glm-5.3-flash:free", "relay/glm-5.3-flash:free",
        "deepseek-flash", "deepseek/deepseek-flash", "x/deepseek_flash-0731", "deepseek-flash-latest",
        "gpt-x.1", "gpt-x-1", "GPT-X.1", "gpt-x.1-preview", "o9-mini", "o9-mini-high",
        "model-x", "model-x:free", "MODEL_X", "model-x-free", "a/b/model-x:free:beta",
        "glm", "glm-5", "unknown-model-zzz", "totally/unknown", "a-b-c-d-e-f",
    )

    private val providers = listOf("Custom", "OpenAI", "Anthropic", "Google", "OpenRouter", "DeepSeek", "NoSuchProvider")

    @Test
    fun theIndexedLookupAnswersExactlyLikeTheOldOne() {
        var hits = 0
        for (provider in providers) for (id in ids) {
            val expected = ModelsDevApi.resolveDevEntryReference(registry, provider, id)
            val actual = ModelsDevApi.resolveDevEntry(registry, provider, id)
            assertEquals("provider=$provider id=$id", expected, actual)
            if (expected != null) hits++
        }
        assertTrue("the fixture must exercise real matches, got $hits", hits > 20)
    }

    @Test
    fun perProviderNormalizedPickIsTheFirstIdInSortedOrder() {
        val index = ModelsDevApi.buildRegistryIndex(registry)
        assertEquals(registry.keys.sorted(), index.sortedProviderKeys)
        for ((provKey, prov) in registry) for (id in prov.models.keys) {
            val wanted = ModelsDevApi.normalizedModelKey(id)
            val old = prov.models.keys.sorted().firstOrNull { ModelsDevApi.normalizedModelKey(it) == wanted }
                ?.let { prov.models[it] }
            // Same OBJECT, not merely an equal one: it must be the same catalog entry.
            assertSame("$provKey/$id", old, index.normalizedFirst[provKey]!![wanted])
            assertSame("$provKey/$id", ModelsDevApi.resolveDevModelReference(prov, id), prov.models[id])
        }
        // "GLM-5.3-Flash" sorts before "glm-5.3-flash" (uppercase first), so it is the pick.
        assertEquals(2, index.normalizedFirst["zeta"]!![ModelsDevApi.normalizedModelKey("glm_5_3_flash")]!!.contextWindow)
    }

    @Test
    fun enrichingAListBuildsTheIndexOnceAndReusesIt() {
        ModelsDevApi.setRegistryForTesting(registry)
        val before = ModelsDevApi.registryIndexBuilds
        val models = ids.map { LLMModel(it, it, "Custom") }
        val first = ModelsDevApi.enrichModels(models)
        val second = ModelsDevApi.enrichModels(models)
        assertEquals("one build for both passes over ${models.size} models", before + 1, ModelsDevApi.registryIndexBuilds)
        assertEquals(first, second)
        assertSame(ModelsDevApi.registryIndex(registry), ModelsDevApi.registryIndex(registry))
        assertEquals(before + 1, ModelsDevApi.registryIndexBuilds)

        // A new registry snapshot gets its own index.
        val other = registry + provider("new", entry("brand-new", 99))
        ModelsDevApi.setRegistryForTesting(other)
        ModelsDevApi.enrichModels(models)
        assertEquals(before + 2, ModelsDevApi.registryIndexBuilds)
    }

    @Test
    fun theHotPathNoLongerSortsTheCatalogPerLookup() {
        val src = ProductionSources.read("provider/ModelsDevApi.kt")
        val body = src.substringAfter("    internal fun resolveDevEntry(").substringBefore("    internal fun resolveDevEntryReference(")
        assertFalse("resolveDevEntry must not sort per call", body.contains(".sorted()"))
        assertTrue(body.contains("val lookup = registryIndex(registry)"))
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertFalse("no per-model DIAG line", repo.contains("replaceEntries lookup key="))
        assertFalse(repo.contains("models.joinToString { m ->"))
    }
}
