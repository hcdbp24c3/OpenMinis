package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-responses-reasoning-echo]
 *
 * Regression lock for the iOS↔Android gap: with `store:false` and
 * `include:["reasoning.encrypted_content"]`, the server returns encrypted
 * reasoning items — but Android never captured them and never replayed them,
 * so prior-turn thinking was silently lost across turns (Responses API then
 * behaves as if the model never thought).
 *
 * iOS reference: `ReasoningEcho` (AgentProvider.swift), capture at
 * response.completed (OpenAIAgentProvider.swift), replay at the head of each
 * assistant turn in `convertMessagesResponsesAPI` (L1656-1685).
 *
 * What is asserted is the request BODY (like ToolResultImageSerializationTest):
 * the defect is in JSON construction, and MockWebServer would only add a
 * network dependency to that question. Capture/SSE is covered separately.
 */
class ResponsesReasoningEchoTest {

    private val model = LLMModel(
        id = "gpt-5.5",
        displayName = "GPT-5.5",
        provider = "openai",
    )

    private val otherModel = model.copy(id = "o3-mini")

    private fun provider(m: LLMModel = model, useResponsesAPI: Boolean = true) =
        OpenAIProvider(
            apiKey = "test-key",
            model = m,
            basePath = "https://example.invalid/v1",
            useResponsesAPI = useResponsesAPI,
        )

    /** Assistant turn carrying a same-model reasoning echo. */
    private fun assistantHistoryWithEcho() = listOf(
        LLMMessage(LLMMessage.Role.USER, "why?"),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(AgentContentPart.Text("because 42")),
            reasoningEcho = com.openminis.app.data.model.ReasoningEcho(
                providerKind = "openai-responses",
                modelId = "gpt-5.5",
                items = listOf(
                    com.openminis.app.data.model.ReasoningEcho.Item.OpenAIReasoning(
                        id = "rs_abc123",
                        encryptedContent = "enc-payload",
                        summary = listOf("thought about it"),
                    ),
                ),
            ),
        ),
    )

    private fun bodyOf(p: OpenAIProvider, messages: List<LLMMessage>): JSONObject =
        p.buildResponsesAPIBody(
            messages = messages,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
        )

    private fun input(body: JSONObject): JSONArray = body.getJSONArray("input")

    /** First index in `input` whose `type` equals [type], or -1. */
    private fun indexOfType(input: JSONArray, type: String): Int {
        for (i in 0 until input.length()) {
            if (input.getJSONObject(i).optString("type") == type) return i
        }
        return -1
    }

    /** First index of a plain `{role:"assistant"}` content item, or -1. */
    private fun indexOfAssistant(input: JSONArray): Int {
        for (i in 0 until input.length()) {
            val o = input.getJSONObject(i)
            if (o.optString("role") == "assistant" && !o.has("type")) return i
        }
        return -1
    }

    @Test
    fun `same-model reasoning echo is replayed as a reasoning input item`() {
        val body = bodyOf(provider(), assistantHistoryWithEcho())
        val inp = input(body)
        val idx = indexOfType(inp, "reasoning")
        assertTrue(
            "reasoning item must be present in input; input=$inp",
            idx >= 0,
        )
        val item = inp.getJSONObject(idx)
        assertEquals("rs_abc123", item.getString("id"))
        assertEquals("enc-payload", item.getString("encrypted_content"))
    }

    @Test
    fun `reasoning item appears at the head of its assistant turn`() {
        val body = bodyOf(provider(), assistantHistoryWithEcho())
        val inp = input(body)
        val reasoningIdx = indexOfType(inp, "reasoning")
        val assistantIdx = indexOfAssistant(inp)
        assertTrue("assistant turn must be present", assistantIdx >= 0)
        assertTrue(
            "reasoning (idx=$reasoningIdx) must precede the assistant content " +
                "(idx=$assistantIdx) of the same turn",
            reasoningIdx in 0 until assistantIdx,
        )
    }

    @Test
    fun `reasoning item appears before function_call of the same turn`() {
        // Responses API rejects reasoning items placed after function_call.
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "run it"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "call_xyz|fc_xyz",
                        name = "shell_execute",
                        input = JSONObject("""{"cmd":"ls"}"""),
                    ),
                ),
                reasoningEcho = com.openminis.app.data.model.ReasoningEcho(
                    providerKind = "openai-responses",
                    modelId = "gpt-5.5",
                    items = listOf(
                        com.openminis.app.data.model.ReasoningEcho.Item.OpenAIReasoning(
                            id = "rs_fc",
                            encryptedContent = "enc",
                            summary = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val inp = input(bodyOf(provider(), history))
        val reasoningIdx = indexOfType(inp, "reasoning")
        val fcIdx = indexOfType(inp, "function_call")
        assertTrue("function_call must be present", fcIdx >= 0)
        assertTrue("reasoning must be present", reasoningIdx >= 0)
        assertTrue(
            "reasoning (idx=$reasoningIdx) must precede function_call (idx=$fcIdx)",
            reasoningIdx < fcIdx,
        )
    }

    @Test
    fun `cross-model echo is stripped`() {
        // encrypted_content is model-specific; replaying it to a different
        // model id is 400-inducing. Same gates as iOS.
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.Text("hello")),
                reasoningEcho = com.openminis.app.data.model.ReasoningEcho(
                    providerKind = "openai-responses",
                    modelId = "gpt-5.5",
                    items = listOf(
                        com.openminis.app.data.model.ReasoningEcho.Item.OpenAIReasoning(
                            id = "rs_old",
                            encryptedContent = "enc-old",
                            summary = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val body = bodyOf(provider(otherModel), history)
        val inp = input(body)
        val types = mutableListOf<String>()
        for (i in 0 until inp.length()) {
            types.add(inp.getJSONObject(i).optString("type"))
        }
        assertFalse(
            "cross-model reasoning echo must not be replayed; types=$types",
            types.contains("reasoning"),
        )
        assertTrue(
            "assistant content itself must survive",
            indexOfAssistant(inp) >= 0,
        )
    }

    @Test
    fun `summary is always an array even when empty`() {
        // Server 400s with "Missing required parameter: 'input[N].summary'"
        // when the field is absent — always emit an array.
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.Text("hey")),
                reasoningEcho = com.openminis.app.data.model.ReasoningEcho(
                    providerKind = "openai-responses",
                    modelId = "gpt-5.5",
                    items = listOf(
                        com.openminis.app.data.model.ReasoningEcho.Item.OpenAIReasoning(
                            id = "rs_empty",
                            encryptedContent = null,
                            summary = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val inp = input(bodyOf(provider(), history))
        val idx = indexOfType(inp, "reasoning")
        assertTrue("reasoning item must be present", idx >= 0)
        val item = inp.getJSONObject(idx)
        assertTrue(
            "summary must be present as an (empty) array",
            item.has("summary") && item.get("summary") is JSONArray,
        )
        assertEquals(0, item.getJSONArray("summary").length())
        assertFalse(
            "null encrypted_content must be omitted, not sent as null",
            item.has("encrypted_content"),
        )
    }

    @Test
    fun `plaintext reasoningText is replayed as content reasoning_text`() {
        // DeepSeek-shaped: content[] reasoning_text, no encrypted_content.
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "why?"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.Text("because 42")),
                reasoningEcho = com.openminis.app.data.model.ReasoningEcho(
                    providerKind = "openai-responses",
                    modelId = "gpt-5.5",
                    items = listOf(
                        com.openminis.app.data.model.ReasoningEcho.Item.OpenAIReasoning(
                            id = "rs_plain",
                            encryptedContent = null,
                            summary = emptyList(),
                            reasoningText = listOf("thinking in plaintext"),
                        ),
                    ),
                ),
            ),
        )
        val inp = input(bodyOf(provider(), history))
        val idx = indexOfType(inp, "reasoning")
        assertTrue("reasoning item must be present; input=$inp", idx >= 0)
        val item = inp.getJSONObject(idx)
        assertTrue("content key must be present", item.has("content"))
        val content = item.getJSONArray("content")
        assertEquals(1, content.length())
        val block = content.getJSONObject(0)
        assertEquals("reasoning_text", block.getString("type"))
        assertEquals("thinking in plaintext", block.getString("text"))
    }

    @Test
    fun `encrypted-only echo omits content key entirely`() {
        // Locked: no "content":[] for encrypted-only fixtures — empty list
        // must omit the key, not emit an empty array.
        val inp = input(bodyOf(provider(), assistantHistoryWithEcho()))
        val idx = indexOfType(inp, "reasoning")
        assertTrue("reasoning item must be present", idx >= 0)
        val item = inp.getJSONObject(idx)
        assertFalse(
            "content key must be omitted when reasoningText is empty",
            item.has("content"),
        )
        assertEquals("enc-payload", item.getString("encrypted_content"))
    }

    @Test
    fun `history without an echo is unchanged`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.Text("hello")),
            ),
        )
        val inp = input(bodyOf(provider(), history))
        assertEquals(
            "no echo → no reasoning item",
            -1,
            indexOfType(inp, "reasoning"),
        )
        assertTrue(indexOfAssistant(inp) >= 0)
    }
}
