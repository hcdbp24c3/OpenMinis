package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-model-metadata-from-api] Android ModelsDevApi matched catalog ids EXACTLY
 * (`prov.models[model.id]`), so a new-api relay serving `deepseek/deepseek-flash`
 * never found the models.dev entry `deepseek-flash` (1M context, reasoning).
 * iOS already had `normalizedModelKey`; Android now mirrors it.
 *
 * These tests run without network: the registry is installed directly via the
 * test seam `setRegistryForTesting`, and `enrichModels`/`enrichModel` are
 * exercised against it. The registry is cleared in @After so no state leaks
 * into sibling test classes (e.g. OpenAIModelsApiParsingTest).
 */
class ModelsDevApiNormalizationTest {

    @Before
    fun setUp() {
        ModelsDevApi.setRegistryForTesting(null)
    }

    @After
    fun tearDown() {
        ModelsDevApi.setRegistryForTesting(null)
    }

    // MARK: - normalizedModelKey

    @Test
    fun normalizedModelKeyStripsVendorPrefixAndFoldsSeparators() {
        assertEquals("deepseek-flash", ModelsDevApi.normalizedModelKey("deepseek/deepseek-flash"))
        assertEquals("glm-5-2", ModelsDevApi.normalizedModelKey("z-ai/GLM-5.2"))
        assertEquals(
            "deepseek-v4-flash",
            ModelsDevApi.normalizedModelKey("accounts/fireworks/models/deepseek-v4-flash"),
        )
        assertEquals("gpt-4o", ModelsDevApi.normalizedModelKey("gpt-4o"))
    }

    // MARK: - enrichModels with normalized matching

    @Test
    fun prefixedRelayIdFindsCatalogEntryByNormalizedKey() {
        // models.dev keys it `deepseek-flash`; the relay serves `deepseek/deepseek-flash`.
        ModelsDevApi.setRegistryForTesting(
            mapOf(
                "deepseek" to provider(
                    "deepseek",
                    "deepseek-flash" to devEntry("deepseek-flash", contextWindow = 1_000_000, reasoning = true),
                ),
            ),
        )

        val enriched = ModelsDevApi.enrichModels(
            listOf(LLMModel("deepseek/deepseek-flash", "DeepSeek Flash", "Custom")),
        )

        val model = enriched[0]
        assertEquals(1_000_000, model.contextWindow)
        assertEquals(true, model.supportsReasoning)
    }

    @Test
    fun exactIdMatchStillWinsOverNormalizedMatch() {
        // Both the exact key and a normalized-colliding key exist in the same
        // provider; exact must win.
        ModelsDevApi.setRegistryForTesting(
            mapOf(
                "deepseek" to provider(
                    "deepseek",
                    "deepseek/deepseek-flash" to devEntry("deepseek/deepseek-flash", contextWindow = 999_999),
                    "deepseek-flash" to devEntry("deepseek-flash", contextWindow = 1_000_000),
                ),
            ),
        )

        val enriched = ModelsDevApi.enrichModels(
            listOf(LLMModel("deepseek/deepseek-flash", "DeepSeek Flash", "Custom")),
        )

        assertEquals(999_999, enriched[0].contextWindow)
    }

    @Test
    fun apiValueWinsOverModelsDevBecauseApplyIsFillIfNil() {
        // The API already told us 1M (Task 1 parse); models.dev says 128K.
        // applyDevData must NOT overwrite the API value.
        ModelsDevApi.setRegistryForTesting(
            mapOf(
                "deepseek" to provider(
                    "deepseek",
                    "deepseek-flash" to devEntry("deepseek-flash", contextWindow = 128_000, reasoning = false),
                ),
            ),
        )

        val enriched = ModelsDevApi.enrichModels(
            listOf(
                LLMModel(
                    "deepseek/deepseek-flash",
                    "DeepSeek Flash",
                    "Custom",
                    contextWindow = 1_000_000,
                    maxOutputTokens = 384_000,
                    supportsReasoning = true,
                ),
            ),
        )

        val model = enriched[0]
        assertEquals("API context must survive enrichment", 1_000_000, model.contextWindow)
        assertEquals("API max output must survive enrichment", 384_000, model.maxOutputTokens)
        assertEquals("API reasoning must survive enrichment", true, model.supportsReasoning)
    }

    @Test
    fun effortPreferenceIsPreservedAcrossNormalizedCandidates() {
        // Two providers both normalize to the same key; the one declaring
        // effort tiers must win (sorted keys + effort-preference, unchanged).
        ModelsDevApi.setRegistryForTesting(
            mapOf(
                "a" to provider(
                    "a",
                    "glm-5.2" to devEntry("glm-5.2", contextWindow = 200_000),
                ),
                "b" to provider(
                    "b",
                    "glm-5.2" to devEntry("glm-5.2", contextWindow = 200_000, reasoning = true, reasoningEffortValues = listOf("high", "max")),
                ),
            ),
        )

        val enriched = ModelsDevApi.enrichModels(
            listOf(LLMModel("z-ai/glm-5.2", "GLM 5.2", "Custom")),
        )

        val model = enriched[0]
        assertEquals(listOf("high", "max"), model.reasoningEffortValues)
        assertEquals(true, model.supportsReasoning)
    }

    @Test
    fun unknownModelPassesThroughUnchanged() {
        ModelsDevApi.setRegistryForTesting(
            mapOf(
                "deepseek" to provider(
                    "deepseek",
                    "deepseek-flash" to devEntry("deepseek-flash", contextWindow = 1_000_000),
                ),
            ),
        )

        val original = LLMModel("totally/unknown-model", "Unknown", "Custom")
        val enriched = ModelsDevApi.enrichModels(listOf(original))

        assertEquals(original, enriched[0])
    }

    // MARK: - helpers

    private fun devEntry(
        id: String,
        contextWindow: Int? = null,
        maxOutputTokens: Int? = null,
        reasoning: Boolean? = null,
        reasoningEffortValues: List<String>? = null,
    ): ModelsDevApi.ModelDevEntry = ModelsDevApi.ModelDevEntry(
        id = id,
        name = null,
        family = null,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        reasoning = reasoning,
        interleavedField = null,
        inputModalities = null,
        outputModalities = null,
        reasoningEffortValues = reasoningEffortValues,
        declaresNoEffortTiers = false,
        releaseDate = null,
        outputCost = null,
    )

    private fun provider(
        id: String,
        vararg models: Pair<String, ModelsDevApi.ModelDevEntry>,
    ): ModelsDevApi.ProviderEntry = ModelsDevApi.ProviderEntry(
        id = id,
        name = id,
        api = null,
        models = models.toMap(),
    )

    @Test
    fun sanityTestHelpersAreUsable() {
        // Guards the helper constructors themselves (compile-time check that
        // all required fields are supplied).
        assertTrue(devEntry("x").id == "x")
        assertTrue(provider("p", "x" to devEntry("x")).models.containsKey("x"))
    }
}