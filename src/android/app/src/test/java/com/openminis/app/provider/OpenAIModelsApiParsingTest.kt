package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-model-metadata-from-api] OpenMinis used to ignore `context_length`,
 * `max_output_tokens` and `reasoning` in OpenAI-compatible /v1/models
 * responses — only id/name/modalities were parsed, so a new-api relay
 * serving `deepseek/deepseek-flash` with `context_length: 1000000,
 * reasoning: true` still showed 128K and no Deep Thinking toggle.
 *
 * These tests exercise the parse loop against a MockWebServer (same idiom
 * as XAIDynamicCatalogTest) so the real HTTP + JSON parsing runs with no
 * network and no credential. `ModelsDevApi.loadRegistry()` returns null in
 * the JVM (no Robolectric, `ModelsDevApi.init()` never called), so
 * `enrichModels` is a no-op and the assertions hit the API-parsed values
 * directly. The registry is explicitly reset in @Before so a registry
 * installed by ModelsDevApiNormalizationTest cannot leak in.
 */
class OpenAIModelsApiParsingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Isolate from any registry a sibling test class installed.
        ModelsDevApi.setRegistryForTesting(null)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Base URL shaped like ProviderConfig.effectiveBaseURL (already carries /v1). */
    private fun baseUrl(): String = server.url("/v1").toString().trimEnd('/')

    private fun fetch(body: String): List<LLMModel> {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        return runBlocking { OpenAIModelsApi.fetchModels("test-key", baseUrl()) }
    }

    @Test
    fun parsesCapabilityFieldsFromTheApiResponse() {
        // Wire format served by the new-api gateway (ai.013666.xyz):
        // {"id":"deepseek/deepseek-flash","context_length":1000000,
        //  "max_output_tokens":384000,"reasoning":true}
        val models = fetch(
            """
            {"object":"list","data":[
              {"id":"deepseek/deepseek-flash","object":"model","owned_by":"deepseek",
               "context_length":1000000,"max_output_tokens":384000,"reasoning":true}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, models.size)
        val model = models[0]
        assertEquals("deepseek/deepseek-flash", model.id)
        assertEquals(1_000_000, model.contextWindow)
        assertEquals(384_000, model.maxOutputTokens)
        assertEquals(true, model.supportsReasoning)
    }

    @Test
    fun knownReasoningFamilyFallsBackWhenApiIsSilent() {
        // gpt-5.5 is in the knownReasoning family but the API says nothing
        // about reasoning — the pill must still enable (knownReasoning path).
        val models = fetch(
            """{"object":"list","data":[{"id":"gpt-5.5","object":"model"}]}""",
        )

        assertEquals(1, models.size)
        assertEquals(true, models[0].supportsReasoning)
    }

    @Test
    fun apiReasoningFalseWinsOverKnownReasoning() {
        // o3 is a knownReasoning family id, but the API affirmatively says
        // reasoning:false — API-first means we trust the gateway.
        val models = fetch(
            """{"object":"list","data":[{"id":"o3","object":"model","reasoning":false}]}""",
        )

        assertEquals(1, models.size)
        assertEquals(false, models[0].supportsReasoning)
    }

    @Test
    fun nullReasoningDoesNotAbortTheParse() {
        // `"reasoning": null` must not throw in getBoolean and kill the whole
        // fetch (which would fall back to an empty picker for custom bases).
        val models = fetch(
            """{"object":"list","data":[{"id":"gpt-5.5","object":"model","reasoning":null}]}""",
        )

        assertEquals(1, models.size)
        assertEquals(true, models[0].supportsReasoning)
    }

    @Test
    fun zeroOrAbsentContextLengthStaysNull() {
        val models = fetch(
            """
            {"object":"list","data":[
              {"id":"gpt-4o","object":"model","context_length":0},
              {"id":"gpt-4o-mini","object":"model"}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, models.size)
        assertNull("context_length:0 must not be treated as a real window", models[0].contextWindow)
        assertNull("absent context_length must stay null", models[1].contextWindow)
        assertTrue("absent max_output_tokens must stay null", models.all { it.maxOutputTokens == null })
    }
}