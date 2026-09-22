package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ReasoningEcho
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-responses-reasoning-echo] Capture half: the stream must surface
 * encrypted reasoning items from `response.completed.output` as a
 * [LLMStreamChunk.ReasoningEcho], so ChatViewModel can stash them for the
 * next turn's replay.
 *
 * Mirrors iOS OpenAIAgentProvider.swift L758-781. Without this capture the
 * include=["reasoning.encrypted_content"] request param is useless — the
 * payload arrives and is dropped.
 */
class ResponsesReasoningEchoCaptureTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private val model = LLMModel(
        id = "gpt-5.5",
        displayName = "GPT-5.5",
        provider = "openai",
        supportsReasoning = true,
    )

    private fun responsesChunks(body: String, m: LLMModel = model): List<LLMStreamChunk> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = m,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        return runBlocking {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.HIGH,
            ).toList()
        }
    }

    private fun completedWithReasoning(
        encrypted: String? = "enc-payload-blob",
        summaryText: String? = "planned the answer",
        id: String = "rs_abc123",
    ): String {
        val summaryJson = if (summaryText != null) {
            """"summary":[{"type":"summary_text","text":"$summaryText"}],"""
        } else {
            """"summary":[],"""
        }
        val encJson = if (encrypted != null) """"encrypted_content":"$encrypted",""" else ""
        // SSE frames must be single-line data: events — multi-line JSON splits
        // the frame and response.completed never parses.
        return (
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"final\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
                "{\"type\":\"reasoning\",\"id\":\"$id\",$encJson$summaryJson\"content\":[]}," +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"final\"}]}" +
                "]},\"sequence_number\":9}\n\n"
            )
    }

    @Test
    fun `response completed reasoning item yields a ReasoningEcho chunk`() {
        val chunks = responsesChunks(completedWithReasoning())
        val echoes = chunks.filterIsInstance<LLMStreamChunk.ReasoningEcho>()
        assertEquals(
            "expected exactly one ReasoningEcho, got $chunks",
            1,
            echoes.size,
        )
        val echo = echoes.first().echo
        assertEquals("openai-responses", echo.providerKind)
        assertEquals("gpt-5.5", echo.modelId)
        assertEquals(1, echo.items.size)
        val item = echo.items.single() as ReasoningEcho.Item.OpenAIReasoning
        assertEquals("rs_abc123", item.id)
        assertEquals("enc-payload-blob", item.encryptedContent)
        assertEquals(listOf("planned the answer"), item.summary)
    }

    @Test
    fun `summary-only item without encrypted_content is still captured`() {
        val chunks = responsesChunks(
            completedWithReasoning(encrypted = null, summaryText = "only text"),
        )
        val item = chunks.filterIsInstance<LLMStreamChunk.ReasoningEcho>()
            .single()
            .echo
            .items
            .single() as ReasoningEcho.Item.OpenAIReasoning
        assertEquals(null, item.encryptedContent)
        assertEquals(listOf("only text"), item.summary)
    }

    @Test
    fun `item with neither encrypted_content nor summary is dropped`() {
        // Nothing useful to replay or display — mirrors iOS guard at L772.
        val chunks = responsesChunks(
            completedWithReasoning(encrypted = null, summaryText = null),
        )
        assertTrue(
            "useless reasoning item must not produce an echo; chunks=$chunks",
            chunks.filterIsInstance<LLMStreamChunk.ReasoningEcho>().isEmpty(),
        )
    }

    @Test
    fun `completed without reasoning items emits no echo`() {
        val chunks = responsesChunks(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"," +
                "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}," +
                "\"sequence_number\":1}\n\n",
        )
        assertFalse(
            "no reasoning in output → no echo",
            chunks.filterIsInstance<LLMStreamChunk.ReasoningEcho>().isNotEmpty(),
        )
        // Sanity: the text still arrives.
        assertEquals(
            "hi",
            chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text },
        )
    }
}
