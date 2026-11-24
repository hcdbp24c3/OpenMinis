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

    // MARK: - [T-model-metadata-from-api] tolerant `reasoning` (refresh bug)

    @Test
    fun reasoningAsAnObjectCountsAsTrueAndDoesNotAbortTheList() {
        // Token Harbor's shape. getBoolean("reasoning") threw here, the whole
        // list was dropped and the refresh silently kept the old models.
        val models = fetch(
            """
            {"object":"list","data":[
              {"id":"vendor/model-a:free","reasoning":{"efforts":["low","high"]},"context_length":200000},
              {"id":"vendor/model-b","reasoning":{"efforts":null}},
              {"id":"vendor/model-c","reasoning":{"supported":false}},
              {"id":"vendor/model-d","reasoning":{"enabled":false,"efforts":[]}},
              {"id":"vendor/model-e"}
            ]}
            """.trimIndent(),
        )

        assertEquals(5, models.size)
        assertEquals("the id keeps its suffix", "vendor/model-a:free", models[0].id)
        assertEquals(true, models[0].supportsReasoning)
        assertEquals(200_000, models[0].contextWindow)
        assertEquals(true, models[1].supportsReasoning)
        assertEquals(false, models[2].supportsReasoning)
        assertEquals(false, models[3].supportsReasoning)
        assertNull(models[4].supportsReasoning)
    }

    @Test
    fun reasoningAsAStringIsParsedAndJunkIsAbsent() {
        val models = fetch(
            """
            {"data":[
              {"id":"m-true","reasoning":"true"},
              {"id":"m-false","reasoning":"FALSE"},
              {"id":"m-junk","reasoning":"sometimes"},
              {"id":"m-number","reasoning":1},
              {"id":"m-array","reasoning":["low"]},
              {"id":"gpt-5.5","reasoning":"n/a"}
            ]}
            """.trimIndent(),
        )

        assertEquals(6, models.size)
        assertEquals(true, models[0].supportsReasoning)
        assertEquals(false, models[1].supportsReasoning)
        assertNull(models[2].supportsReasoning)
        assertNull(models[3].supportsReasoning)
        assertNull(models[4].supportsReasoning)
        assertEquals("unreadable -> the knownReasoning family rule fills in", true, models[5].supportsReasoning)
    }

    @Test
    fun reportedReasoningReaderCoversEveryShape() {
        fun r(json: String) = OpenAIModelsApi.reportedReasoning(org.json.JSONObject(json))
        assertEquals(true, r("""{"reasoning":true}"""))
        assertEquals(false, r("""{"reasoning":false}"""))
        assertEquals(true, r("""{"reasoning":{}}"""))
        assertEquals(true, r("""{"reasoning":{"supported":true}}"""))
        assertEquals(false, r("""{"reasoning":{"supported":"false"}}"""))
        assertEquals(true, r("""{"reasoning":" True "}"""))
        assertNull(r("""{"reasoning":null}"""))
        assertNull(r("""{"id":"m"}"""))
        assertNull(r("""{"reasoning":0}"""))
    }

    @Test
    fun anEntryWithoutAnIdIsSkippedNotFatal() {
        val models = fetch(
            """
            {"data":[
              {"object":"model","context_length":1000},
              {"id":"","object":"model"},
              {"id":"kept-model","reasoning":true},
              null,
              {"id":"   "}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("kept-model"), models.map { it.id })
        assertEquals(true, models[0].supportsReasoning)
    }

    @Test
    fun aReportedReasoningFlagMarksTheModelAsEndpointDescribed() {
        val models = fetch("""{"data":[{"id":"m-a","reasoning":false},{"id":"m-b"}]}""")
        assertEquals(true, models[0].limitsReportedByEndpoint)
        assertNull(models[1].limitsReportedByEndpoint)
    }

    // MARK: - [T-android-model-list-timeouts]

    @Test
    fun aSlowFirstResponseStillLoadsTheList() {
        // The gateway that prompted this took 12.3 s on an uncached request;
        // OkHttp's default read timeout is 10 s. 11 s here proves the list
        // client no longer uses the default.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":[{"id":"slow-model"}]}""")
                .setHeadersDelay(11, java.util.concurrent.TimeUnit.SECONDS),
        )
        val models = runBlocking { OpenAIModelsApi.fetchModels("test-key", baseUrl()) }
        assertEquals(listOf("slow-model"), models.map { it.id })
    }

    @Test
    fun aNetworkFailureIsThrownWithAReadableReason() {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/v1").toString().trimEnd('/')
        dead.shutdown()
        val error = try {
            runBlocking { OpenAIModelsApi.fetchModelsDetailed("test-key", url) }
            null
        } catch (e: java.io.IOException) {
            e
        }
        assertTrue("a dead endpoint must throw, not return an empty list", error != null)
        assertTrue(error!!.message, error.message!!.startsWith("could not reach the provider's model list"))
        assertTrue(
            OpenAIModelsApi.networkFailureMessage(java.net.SocketTimeoutException("timeout"))
                .startsWith("the model list request timed out"),
        )
    }
}
