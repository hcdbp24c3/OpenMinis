import SwiftUI

/// [T-ios-ask-user] Payload for the ask sheet: the questions the agent is parked
/// on, wrapped so `.sheet(item:)` can present it.
struct AskQuestionsPayload: Identifiable {
    let id = UUID()
    let questions: [AskUserQuestion.Question]
}

/// [T-ios-ask-user] The agent's question, as a modal sheet.
///
/// Modal rather than an inline card because the tool BLOCKS: the run is parked on
/// the answer, so a card the user could scroll past would leave the chat looking
/// idle while the agent waits.
///
/// Free text is always available ("Other"), because the model's options are a guess
/// about what the user wants — the MCP this ports makes the same promise.
struct AskUserQuestionsSheet: View {
    let questions: [AskUserQuestion.Question]
    let onSubmit: ([[String]]) -> Void
    let onSkip: () -> Void

    @State private var selections: [Set<String>]
    @State private var freeText: [String]
    @Environment(\.dismiss) private var dismiss

    init(
        questions: [AskUserQuestion.Question],
        onSubmit: @escaping ([[String]]) -> Void,
        onSkip: @escaping () -> Void
    ) {
        self.questions = questions
        self.onSubmit = onSubmit
        self.onSkip = onSkip
        _selections = State(initialValue: Array(repeating: [], count: questions.count))
        _freeText = State(initialValue: Array(repeating: "", count: questions.count))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(Array(questions.enumerated()), id: \.offset) { index, question in
                        questionBlock(index: index, question: question)
                    }
                }
                .padding(16)
            }
            .navigationTitle(AppLocalized("The agent needs your input"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Skip")) {
                        onSkip()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalized("Submit")) {
                        // An empty answer is allowed: the model gets "no answer"
                        // rather than a fabricated one, which is what Skip means
                        // too — the difference is the reason string that travels
                        // with it.
                        let answers = questions.indices.map { index -> [String] in
                            let free = freeText[index].trimmingCharacters(in: .whitespacesAndNewlines)
                            return free.isEmpty ? Array(selections[index]).sorted() : [free]
                        }
                        onSubmit(answers)
                        dismiss()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func questionBlock(index: Int, question: AskUserQuestion.Question) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(question.header.isEmpty ? question.question : question.header)
                .font(.headline)
            if !question.header.isEmpty, question.header != question.question {
                Text(question.question)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            ForEach(question.options, id: \.label) { option in
                Button {
                    if question.multiSelect {
                        if selections[index].contains(option.label) {
                            selections[index].remove(option.label)
                        } else {
                            selections[index].insert(option.label)
                        }
                    } else {
                        selections[index] = [option.label]
                        // Picking an option means the free-text answer to THIS
                        // question is no longer wanted; keeping both would send
                        // two contradictory answers.
                        freeText[index] = ""
                    }
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: selections[index].contains(option.label)
                            ? (question.multiSelect ? "checkmark.square.fill" : "largecircle.fill.circle")
                            : (question.multiSelect ? "square" : "circle"))
                            .foregroundStyle(selections[index].contains(option.label) ? Color.accentColor : .secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.label)
                                .foregroundStyle(.primary)
                            if !option.description.isEmpty {
                                Text(option.description)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                }
                .buttonStyle(.plain)
            }

            TextField(AppLocalized("Other — type your own answer"), text: Binding(
                get: { freeText[index] },
                set: { newValue in
                    freeText[index] = newValue
                    if !newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        selections[index] = []
                    }
                }
            ))
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled()
        }
    }
}
