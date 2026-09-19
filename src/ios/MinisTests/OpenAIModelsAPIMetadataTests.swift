import XCTest
@testable import Minis

/// [T-model-metadata-from-api] OpenMinis used to ignore `context_length`,
/// `max_output_tokens` and `reasoning` in OpenAI-compatible /v1/models
/// responses — only id/name/modalities were parsed, so a new-api relay
/// serving `deepseek/deepseek-flash` with `context_length: 1000000,
/// reasoning: true` still showed 128K and no Deep Thinking toggle.
///
/// These tests exercise the extracted `OpenAIModelsAPI.parseModels` directly
/// with dictionary fixtures — no network, no URLProtocol stub.
final class OpenAIModelsAPIMetadataTests: XCTestCase {

    private func parse(_ items: [[String: Any]], filterOpenAIOnly: Bool = false) -> [LLMModel] {
        OpenAIModelsAPI.parseModels(items, filterOpenAIOnly: filterOpenAIOnly)
    }

    func testParsesCapabilityFieldsFromTheApiResponse() {
        // Wire format served by the new-api gateway (ai.013666.xyz):
        // {"id":"deepseek/deepseek-flash","context_length":1000000,
        //  "max_output_tokens":384000,"reasoning":true}
        let models = parse([
            [
                "id": "deepseek/deepseek-flash",
                "object": "model",
                "context_length": 1_000_000,
                "max_output_tokens": 384_000,
                "reasoning": true,
            ],
        ])

        XCTAssertEqual(models.count, 1)
        let model = models[0]
        XCTAssertEqual(model.id, "deepseek/deepseek-flash")
        XCTAssertEqual(model.contextWindow, 1_000_000)
        XCTAssertEqual(model.maxOutputTokens, 384_000)
        XCTAssertEqual(model.supportsReasoning, true)
    }

    func testApiReasoningFalseIsKept() {
        // API-first: an affirmative `reasoning: false` must not be dropped.
        let models = parse([["id": "o3", "reasoning": false]])
        XCTAssertEqual(models.count, 1)
        XCTAssertEqual(models[0].supportsReasoning, false)
    }

    func testNullReasoningYieldsNilWithoutCrashing() {
        // `"reasoning": NSNull` → `as? Bool` yields nil; no crash, no throw.
        let models = parse([["id": "gpt-5.5", "reasoning": NSNull()]])
        XCTAssertEqual(models.count, 1)
        XCTAssertNil(models[0].supportsReasoning)
    }

    func testZeroOrAbsentContextLengthStaysNil() {
        let models = parse([
            ["id": "gpt-4o", "context_length": 0],
            ["id": "gpt-4o-mini"],
        ])

        XCTAssertEqual(models.count, 2)
        XCTAssertNil(models[0].contextWindow, "context_length:0 must not be treated as a real window")
        XCTAssertNil(models[1].contextWindow, "absent context_length must stay nil")
        XCTAssertTrue(models.allSatisfy { $0.maxOutputTokens == nil })
    }

    func testFilterOpenAIOnlyStillApplies() {
        // Sanity: the refactor must not have changed filtering behaviour.
        let models = parse(
            [
                ["id": "gpt-5.5"],
                ["id": "deepseek/deepseek-flash"],
                ["id": "whisper-1"],
            ],
            filterOpenAIOnly: true,
        )

        XCTAssertEqual(models.map(\.id), ["gpt-5.5"])
    }
}