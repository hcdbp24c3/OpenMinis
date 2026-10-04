package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-thinking-auto] `ThinkingLevel.AUTO` — "let the endpoint choose".
 *
 * Ported (semantics only, no code) from Kelivo's `ReasoningLevel.auto`
 * (`lib/core/services/api/reasoning/reasoning_dialects.dart`): the request carries
 * NO tier, only the surface flag each vendor needs to RETURN thinking at all, each
 * put-if-absent, and nothing the caller already set is stripped.
 *
 * These tests pin the two halves that can regress independently:
 *   1. the wire — no tier is emitted, on every path that used to emit one;
 *   2. the picker — AUTO survives the `rank <= ceiling` filters, because being
 *      appended last gave it the HIGHEST ordinal and every filter silently
 *      dropped it (see ThinkingLevel.rank).
 *
 * [T-thinking-auto] is deliberately NOT a default: existing levels keep their
 * behaviour, so each test asserts a control case alongside the AUTO case.
 */
class ThinkingAutoLevelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── OpenAI-compatible resolver path ──────────────────────────────────────

    private fun ctx(
        level: ThinkingLevel,
        modelId: String = "relay/model-x",
        declared: List<String>? = null,
        isOpenRouter: Boolean = false,
        offEffort: String? = null,
    ) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = null,
        supportsReasoning = true,
        declaredEffortValues = declared,
        declaresNoEffortTiers = false,
        level = level,
        maxTokens = 4096,
        isOpenRouter = isOpenRouter,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        isCerebras = false,
        isXAI = false,
        offEffort = offEffort,
    )

    @Test
    fun `auto sends no tier even when the catalog declares none`() {
        val body = JSONObject()
        ThinkingRuleResolver.apply(body, ctx(ThinkingLevel.AUTO))

        // The whole point: a model the catalog does not describe gets no guessed
        // tier — the request is left to the endpoint's own default.
        for (key in listOf("reasoning_effort", "reasoning", "thinking", "enable_thinking")) {
            assertFalse("AUTO must not emit $key", body.has(key))
        }
    }

    @Test
    fun `auto does not strip keys the caller already set`() {
        val body = JSONObject().apply {
            put("temperature", 0.2)
            put("reasoning_effort", "high") // e.g. a user-authored custom rule
        }
        ThinkingRuleResolver.apply(body, ctx(ThinkingLevel.AUTO))

        // Kelivo's auto is non-destructive: it writes nothing and removes nothing.
        assertEquals(0.2, body.getDouble("temperature"), 0.0)
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun `an explicit level still emits its tier`() {
        // Control: the AUTO branch must not swallow the other levels.
        val body = JSONObject()
        ThinkingRuleResolver.apply(body, ctx(ThinkingLevel.HIGH))
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun `auto on openrouter writes no reasoning object`() {
        val body = JSONObject()
        ThinkingRuleResolver.apply(
            body,
            ctx(ThinkingLevel.AUTO, modelId = "some/model", isOpenRouter = true),
        )
        assertFalse(body.has("reasoning"))
        assertFalse(body.has("reasoning_effort"))
    }

    // ── Gemini ───────────────────────────────────────────────────────────────

    @Test
    fun `auto asks gemini for thoughts and lets it choose the budget`() {
        for (id in listOf("gemini-3-flash-preview", "gemini-2.5-pro", "gemini-2.5-flash")) {
            val cfg = ThinkingRuleResolver.geminiThinkingConfig(id, ThinkingLevel.AUTO)
            assertEquals("$id: includeThoughts", true, cfg?.optBoolean("includeThoughts"))
            assertFalse("$id: no thinkingBudget for AUTO", cfg!!.has("thinkingBudget"))
            assertFalse("$id: no thinkingLevel for AUTO", cfg.has("thinkingLevel"))
        }
    }

    @Test
    fun `auto still sends nothing to a model that rejects thinking`() {
        // The no-thinking suffix guard outranks the level, AUTO included.
        assertNull(ThinkingRuleResolver.geminiThinkingConfig("gemini-2.5-pro-tts", ThinkingLevel.AUTO))
    }

    @Test
    fun `an explicit gemini level still sends its budget`() {
        val cfg = ThinkingRuleResolver.geminiThinkingConfig("gemini-2.5-pro", ThinkingLevel.HIGH)
        assertEquals(16384, cfg!!.optInt("thinkingBudget"))
    }

    // ── Anthropic shape ──────────────────────────────────────────────────────

    @Test
    fun `auto anthropic shape asks adaptive models without an effort`() {
        val adaptive = ThinkingRuleResolver.anthropicThinkingShape(
            modelId = "claude-opus-4-6",
            supportsReasoning = true,
            level = ThinkingLevel.AUTO,
            maxTokens = 8192,
        )
        // `adaptive` with no `effort`: the endpoint picks the depth, and the body
        // builder adds `display:"summarized"` so the thinking text is readable.
        assertEquals(true, adaptive["adaptive"])
        assertNull("AUTO must not pick an effort", adaptive["effort"])
    }

    @Test
    fun `auto anthropic shape sends nothing to legacy models`() {
        // ≤4.5 defaults to no thinking, and a budget would be a tier we have no
        // evidence for — so AUTO writes nothing rather than guessing (Kelivo's set
        // of auto surfaces does not include the budget dialect).
        val legacy = ThinkingRuleResolver.anthropicThinkingShape(
            modelId = "claude-haiku-4-5",
            supportsReasoning = true,
            level = ThinkingLevel.AUTO,
            maxTokens = 8192,
        )
        assertTrue("legacy AUTO must be empty: $legacy", legacy.isEmpty())
    }

    // ── Picker / ranking invariants ──────────────────────────────────────────

    @Test
    fun `auto ranks below off so no picker filter drops it`() {
        assertTrue(
            "AUTO must rank below OFF",
            ThinkingLevel.AUTO.rank < ThinkingLevel.OFF.rank,
        )
        // The three pickers build their options as
        // `entries.filter { ceiling != OFF && it != OFF && it.rank <= ceiling.rank }`.
        // AUTO is in that list for every usable ceiling — being appended last gave it
        // the highest ordinal, which would have filtered it out of every one of them.
        for (ceiling in ThinkingLevel.entries.filter { it != ThinkingLevel.OFF }) {
            val offered = ThinkingLevel.entries.filter {
                it != ThinkingLevel.OFF && it.rank <= ceiling.rank
            }
            assertTrue("AUTO missing at ceiling $ceiling", offered.contains(ThinkingLevel.AUTO))
        }
        // And a ceiling of OFF still means "no levels": AUTO ranks below OFF, so the
        // callers' explicit `ceiling != OFF` guard is what holds this.
        assertEquals(-1, ThinkingLevel.AUTO.rank)
    }

    @Test
    fun `auto survives the clamped entry point and the persisted spelling`() {
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel("relay/model-x", "Relay X", "Custom", supportsReasoning = true),
            basePath = "https://example.invalid/v1",
            useResponsesAPI = true,
        )
        // clampThinkingLevel must pass AUTO straight through (it has no tier to cap).
        assertEquals(ThinkingLevel.AUTO, provider.clampThinkingLevel(ThinkingLevel.AUTO))
        // Both spellings Ionic and Android persist.
        assertEquals(ThinkingLevel.AUTO, ThinkingLevel.parseOrNull("auto"))
        assertEquals(ThinkingLevel.AUTO, ThinkingLevel.parseOrNull("AUTO"))
        assertEquals(ThinkingLevel.AUTO, ThinkingLevel.decoded("auto"))
    }

    // ── Wire: the two provider-local injection sites ─────────────────────────

    @Test
    fun `responses body carries the summary flag and no effort for auto`() {
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel("gpt-5.5", "GPT-5.5", "openai", supportsReasoning = true),
            basePath = "https://example.invalid/v1",
            useResponsesAPI = true,
        )
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi"))

        val auto = provider.buildResponsesAPIBody(
            messages = messages,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            thinkingLevel = ThinkingLevel.AUTO,
        )
        val reasoning = auto.getJSONObject("reasoning")
        assertEquals("auto", reasoning.getString("summary"))
        assertFalse("AUTO must send no effort", reasoning.has("effort"))

        // Control: an explicit level keeps its effort beside the same summary flag.
        val high = provider.buildResponsesAPIBody(
            messages = messages,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            thinkingLevel = ThinkingLevel.HIGH,
        )
        assertEquals("high", high.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun `anthropic body asks adaptive thinking without output_config for auto`() = runBlocking {
        val body = anthropicBodyFor(LLMModel.claudeOpus46, ThinkingLevel.AUTO)
        val thinking = body.getJSONObject("thinking")
        assertEquals("adaptive", thinking.getString("type"))
        assertEquals("summarized", thinking.getString("display"))
        assertFalse("AUTO must not set an effort", body.has("output_config"))
        assertFalse("no budget_tokens on the adaptive path", thinking.has("budget_tokens"))
    }

    @Test
    fun `anthropic body sends no thinking to a legacy model for auto`() = runBlocking {
        val body = anthropicBodyFor(LLMModel.claudeHaiku45, ThinkingLevel.AUTO)
        assertFalse("AUTO on a legacy model sends nothing", body.has("thinking"))
    }

    @Test
    fun `anthropic body keeps its explicit effort path for a level`() = runBlocking {
        // Control: HIGH on the same adaptive model still writes output_config.effort.
        val body = anthropicBodyFor(LLMModel.claudeOpus46, ThinkingLevel.HIGH)
        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
        assertEquals("high", body.getJSONObject("output_config").getString("effort"))
    }

    /** Drive the real Anthropic provider through MockWebServer and read its body. */
    private fun anthropicBodyFor(model: LLMModel, level: ThinkingLevel): JSONObject {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",""" +
                    """"usage":{"input_tokens":1,"output_tokens":1}}""",
            ),
        )
        val provider = AnthropicProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/").toString().trimEnd('/'),
        )
        runBlocking {
            provider.sendMessage(
                listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 4096,
                thinkingLevel = level,
            )
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }
}
