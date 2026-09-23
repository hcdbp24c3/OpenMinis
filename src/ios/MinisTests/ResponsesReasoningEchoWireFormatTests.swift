import XCTest
@testable import Minis

/// [T-android-responses-reasoning-echo] iOS wire-format contract lock for
/// Responses-API reasoning replay — mirrors Android
/// `ResponsesReasoningEchoTest` (`plaintext reasoningText is replayed as
/// content reasoning_text` + `encrypted-only echo omits content key
/// entirely`).
///
/// DeepSeek-shaped reasoning items carry plaintext `content[].reasoning_text`
/// and no `encrypted_content`; the API rejects the reasoning item on the next
/// turn with `The reasoning_text in the thinking mode must be passed back to
/// the API` unless the plaintext blocks are echoed. Encrypted-only fixtures
/// (OpenAI `include:["reasoning.encrypted_content"]`) must NOT gain a
/// `"content":[]` key — omit the key entirely when the list is empty.
///
/// Exercises production `OpenAIAgentProvider.convertMessagesResponsesAPI`
/// (internal seam, same pattern as `injectThinkingParams` + Android
/// `buildResponsesAPIBody`) so assertions target the REAL emitted body, never
/// a reimplementation. Cannot run on Linux (no Xcode) — contract is locked
/// here for future macOS/CI runs; Android tests are the executable oracle.
final class ResponsesReasoningEchoWireFormatTests: XCTestCase {

    private let model = LLMModel(
        id: "gpt-5.5",
        displayName: "GPT-5.5",
        provider: "openai"
    )

    private func provider(model m: LLMModel? = nil) -> OpenAIAgentProvider {
        let m = m ?? model
        let openAI = OpenAIProvider(
            apiKey: "test-key",
            model: m,
            customBaseURL: "https://example.invalid/v1"
        )
        return OpenAIAgentProvider(provider: openAI)
    }

    /// Assistant turn carrying a same-model reasoning echo.
    private func assistantHistory(
        id: String,
        encryptedContent: String?,
        summary: [String],
        reasoningText: [String]
    ) -> [AgentMessage] {
        let echo = ReasoningEcho(
            providerKind: OpenAIAgentProvider.responsesAPIProviderKind,
            modelId: model.id,
            items: [
                .openaiReasoning(
                    id: id,
                    encryptedContent: encryptedContent,
                    summary: summary,
                    reasoningText: reasoningText
                )
            ]
        )
        var assistant = AgentMessage(role: .assistant, parts: [.text("because 42")])
        assistant.reasoningEcho = echo
        return [
            AgentMessage(role: .user, parts: [.text("why?")]),
            assistant
        ]
    }

    /// First `type == "reasoning"` entry in the converted input, or nil.
    private func reasoningEntry(
        from messages: [AgentMessage],
        provider p: OpenAIAgentProvider? = nil
    ) -> [String: Any]? {
        let input = (p ?? provider()).convertMessagesResponsesAPI(messages)
        return input.first { $0["type"] as? String == "reasoning" }
    }

    // MARK: - Plaintext content[] round-trip (DeepSeek shape)

    func testPlaintextReasoningTextIsReplayedAsContentReasoningText() {
        // Mirrors Android `plaintext reasoningText is replayed as content
        // reasoning_text` — content-only item, no encrypted_content.
        let entry = reasoningEntry(from: assistantHistory(
            id: "rs_plain",
            encryptedContent: nil,
            summary: [],
            reasoningText: ["thinking in plaintext"]
        ))
        XCTAssertNotNil(entry, "reasoning item must be present in converted input")
        guard let entry else { return }

        XCTAssertEqual(entry["type"] as? String, "reasoning")
        XCTAssertEqual(entry["id"] as? String, "rs_plain")
        XCTAssertNil(
            entry["encrypted_content"],
            "null encrypted_content must be omitted, not sent as null"
        )

        // summary stays a required (possibly empty) array.
        let summary = entry["summary"] as? [[String: Any]]
        XCTAssertNotNil(summary, "summary must be present as an array")
        XCTAssertEqual(summary?.count, 0)

        // The locked contract: content[] with type reasoning_text + text.
        let content = entry["content"] as? [[String: Any]]
        XCTAssertNotNil(
            content,
            "content key must be present when reasoningText is non-empty"
        )
        XCTAssertEqual(content?.count, 1)
        XCTAssertEqual(content?.first?["type"] as? String, "reasoning_text")
        XCTAssertEqual(content?.first?["text"] as? String, "thinking in plaintext")
    }

    // MARK: - Encrypted-only omits content key entirely

    func testEncryptedOnlyEchoOmitsContentKeyEntirely() {
        // Locked: no `"content":[]` for encrypted-only fixtures — empty list
        // must omit the key, not emit an empty array. Mirrors Android
        // `encrypted-only echo omits content key entirely`.
        let entry = reasoningEntry(from: assistantHistory(
            id: "rs_abc123",
            encryptedContent: "enc-payload",
            summary: ["thought about it"],
            reasoningText: []
        ))
        XCTAssertNotNil(entry, "reasoning item must be present in converted input")
        guard let entry else { return }

        XCTAssertEqual(entry["id"] as? String, "rs_abc123")
        XCTAssertEqual(entry["encrypted_content"] as? String, "enc-payload")
        XCTAssertNil(
            entry["content"],
            "content key must be omitted when reasoningText is empty"
        )
        let summary = entry["summary"] as? [[String: Any]]
        XCTAssertEqual(summary?.count, 1)
        XCTAssertEqual(summary?.first?["type"] as? String, "summary_text")
        XCTAssertEqual(summary?.first?["text"] as? String, "thought about it")
    }

    // MARK: - Gates (must NOT change)

    func testCrossModelEchoIsStripped() {
        // Same gates as Android: encrypted_content (and plaintext) are only
        // safe to echo back to the SAME model id within the same family.
        let echo = ReasoningEcho(
            providerKind: OpenAIAgentProvider.responsesAPIProviderKind,
            modelId: "gpt-5.5",
            items: [
                .openaiReasoning(
                    id: "rs_old",
                    encryptedContent: "enc-old",
                    summary: [],
                    reasoningText: ["stale plaintext"]
                )
            ]
        )
        var assistant = AgentMessage(role: .assistant, parts: [.text("hello")])
        assistant.reasoningEcho = echo
        let messages = [
            AgentMessage(role: .user, parts: [.text("hi")]),
            assistant
        ]

        // Provider bound to a DIFFERENT model id — echo must not be replayed.
        let other = LLMModel(id: "o3-mini", displayName: "o3-mini", provider: "openai")
        let input = provider(model: other).convertMessagesResponsesAPI(messages)
        let types = input.compactMap { $0["type"] as? String }
        XCTAssertFalse(
            types.contains("reasoning"),
            "cross-model reasoning echo must not be replayed; types=\(types)"
        )
        // Assistant content itself must survive.
        XCTAssertTrue(
            input.contains { $0["role"] as? String == "assistant" },
            "assistant content must survive cross-model strip"
        )
    }

    func testHistoryWithoutEchoIsUnchanged() {
        let messages = [
            AgentMessage(role: .user, parts: [.text("hi")]),
            AgentMessage(role: .assistant, parts: [.text("hello")])
        ]
        let input = provider().convertMessagesResponsesAPI(messages)
        XCTAssertFalse(
            input.contains { $0["type"] as? String == "reasoning" },
            "no echo → no reasoning item"
        )
        XCTAssertTrue(input.contains { $0["role"] as? String == "assistant" })
    }
}
