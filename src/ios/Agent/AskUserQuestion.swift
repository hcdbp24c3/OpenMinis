import Foundation

/// [T-ios-ask-user] Structured multiple-choice questions the agent can pose when a
/// choice is genuinely ambiguous.
///
/// Android twin: `tools/AskUserQuestion.kt` (which itself ports
/// https://github.com/paulp-o/ask-user-questions-mcp, via tall-1997/OpenMinis-Linux,
/// GPL-3) — same shapes, so a model that learned one client's `ask_user_question`
/// call works against the other.
///
/// This is the DATA half: parse what the model sent, format what the user picked.
/// The blocking half lives in `AIChatViewModel.executeAskUserQuestion`, which parks
/// the agent loop on a continuation until the sheet is answered — a model that
/// asks a question is really stopped until a human replies.
enum AskUserQuestion {
    static let name = "ask_user_question"
    static let alias = "AskUserQuestion"

    struct Option: Equatable {
        let label: String
        let description: String
    }

    struct Question: Equatable, Identifiable {
        let id = UUID()
        let question: String
        let header: String
        let options: [Option]
        let multiSelect: Bool

        static func == (lhs: Question, rhs: Question) -> Bool {
            lhs.question == rhs.question && lhs.header == rhs.header &&
                lhs.options == rhs.options && lhs.multiSelect == rhs.multiSelect
        }
    }

    static func definition() -> AgentToolDefinition {
        AgentToolDefinition(
            name: name,
            description: """
            Ask the user one or more structured questions when a choice is genuinely ambiguous (product direction, mutually exclusive options, missing preference). Do NOT use this to ask permission for routine tool calls — just do those.

            Mirrors the ask-user-questions MCP (`AskUserQuestion`). Pass `questions` as a JSON array of 1–4 items. Each item:
              question (string, required)
              header (short label, optional)
              options: 2–4 objects {label, description}
              multiSelect (boolean, default false)

            The tool blocks until the user answers. Free-text is allowed via an Other option the UI always offers.
            """,
            parameters: [
                "tool_title": AgentToolParam(type: .string, description: "Short live-status title, e.g. 'Choose backup location'."),
                "questions": AgentToolParam(
                    type: .string,
                    description: "JSON array of questions (see tool description). Also accepted as a JSON array value."
                ),
            ],
            required: ["questions"]
        )
    }

    /// Parse the tool arguments into renderable questions.
    ///
    /// Accepts an array, a JSON *string* holding an array (models emit both), or a
    /// single object, and drops anything a sheet could not render — a question with
    /// no text, or a "choice" with fewer than two options. Silently keeping a
    /// one-option question would let the model pretend the user chose.
    static func parse(_ params: [String: Any]) -> [Question] {
        let raw = params["questions"]
        var array: [[String: Any]] = []
        switch raw {
        case let value as [[String: Any]]:
            array = value
        case let value as [String: Any]:
            array = [value]
        case let value as String:
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty,
               let data = trimmed.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: data) {
                if let list = parsed as? [[String: Any]] {
                    array = list
                } else if let single = parsed as? [String: Any] {
                    array = [single]
                }
            }
        default:
            array = []
        }

        var questions: [Question] = []
        for item in array {
            let text = (item["question"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            var options: [Option] = []
            for optionValue in (item["options"] as? [[String: Any]] ?? []) {
                let label = (optionValue["label"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                guard !label.isEmpty else { continue }
                options.append(Option(
                    label: label,
                    description: (optionValue["description"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                ))
            }
            guard options.count >= 2 else { continue }
            questions.append(Question(
                question: text,
                header: (item["header"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                options: Array(options.prefix(4)),
                multiSelect: item["multiSelect"] as? Bool ?? false
            ))
            if questions.count >= 4 { break }
        }
        return questions
    }

    /// The answers, as the JSON the model gets back as the tool result.
    static func formatAnswers(_ questions: [Question], selections: [[String]]) -> String {
        var answers: [[String: Any]] = []
        for (index, question) in questions.enumerated() {
            let picked = index < selections.count ? selections[index] : []
            answers.append([
                "question": question.question,
                "header": question.header,
                "answers": picked,
            ])
        }
        let root: [String: Any] = ["answers": answers]
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8) else {
            return "{\"answers\":[]}"
        }
        return json
    }
}
