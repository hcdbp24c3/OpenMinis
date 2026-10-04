import SwiftUI

// MARK: - [T-ios-web-search] Settings › Tools › Web search

/// Engine + credentials for the `web_search` tool. Android twin:
/// `ui/settings/WebSearchSettingsScreen.kt` — same engines, same fields, same
/// wording, so the two clients describe one contract to the user.
///
/// List = engines (tap one to configure it); the detail page carries the
/// "use this engine" switch and the credential field. DuckDuckGo needs no key
/// and is the default, so a fresh install works without ever opening this
/// screen.
struct WebSearchSettingsView: View {
    @State private var engine: WebSearchEngine = WebSearchSettings.engine
    @State private var fallback: Bool = WebSearchSettings.fallbackEnabled

    var body: some View {
        Form {
            Section {
                ForEach(WebSearchEngine.allCases, id: \.self) { item in
                    NavigationLink {
                        WebSearchEngineDetailView(engine: item, selected: $engine)
                    } label: {
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Self.label(item))
                                Text(subtitle(for: item))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if engine == item {
                                Image(systemName: "checkmark")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            } header: {
                Text(AppLocalized("Engine"))
            } footer: {
                Text(AppLocalized("Tap an engine to configure it. DuckDuckGo needs no key. SearXNG needs an instance URL. Bing needs an API key. Custom uses a URL template with {query}. Failed searches fall back to DuckDuckGo, then suggest browser_use."))
            }

            Section {
                Toggle(AppLocalized("Fallback to DuckDuckGo"), isOn: $fallback)
                    // iOS 16 form (the two-parameter closure is iOS 17+), which
                    // is what every other call site in this repo uses.
                    .onChange(of: fallback) { newValue in
                        WebSearchSettings.setFallbackEnabled(newValue)
                    }
            } footer: {
                Text(AppLocalized("If the selected engine fails, retry DuckDuckGo. Empty results still recommend browser_use on a known URL."))
            }
        }
        .navigationTitle(AppLocalized("Web search"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // Re-read on appear: the detail page writes the engine, and coming
            // back must show the new checkmark without a second source of truth.
            engine = WebSearchSettings.engine
            fallback = WebSearchSettings.fallbackEnabled
        }
    }

    /// Configured / not-configured line under each engine, mirroring Android.
    private func subtitle(for item: WebSearchEngine) -> String {
        switch item {
        case .ddg:
            return AppLocalized("No API key needed")
        case .searxng:
            return WebSearchSettings.searxngURL.isEmpty
                ? AppLocalized("Not configured")
                : AppLocalized("Configured")
        case .custom:
            return WebSearchSettings.customURL.isEmpty
                ? AppLocalized("Not configured")
                : AppLocalized("Configured")
        default:
            return WebSearchSettings.apiKey(for: item).isEmpty
                ? AppLocalized("Not configured")
                : AppLocalized("Configured")
        }
    }

    static func label(_ engine: WebSearchEngine) -> String {
        switch engine {
        case .ddg: return AppLocalized("DuckDuckGo HTML")
        case .searxng: return AppLocalized("SearXNG")
        case .bing: return AppLocalized("Bing Web Search API")
        case .tavily: return AppLocalized("Tavily")
        case .bocha: return AppLocalized("Bocha")
        case .exa: return AppLocalized("Exa")
        case .brave: return AppLocalized("Brave Search")
        case .jina: return AppLocalized("Jina Search")
        case .zhipu: return AppLocalized("Zhipu Web Search")
        case .custom: return AppLocalized("Custom")
        }
    }

    static func detailFooter(_ engine: WebSearchEngine) -> String {
        switch engine {
        case .ddg:
            return AppLocalized("HTML search with no credentials. Default engine.")
        case .searxng:
            return AppLocalized("Public or self-hosted SearXNG instance. Paste the search endpoint or site origin.")
        case .bing:
            return AppLocalized("Microsoft Bing Web Search v7 subscription key.")
        case .custom:
            return AppLocalized("Use {query} (and optional {key}) in the URL. JSON arrays named results/items/data are parsed; HTML falls back to DuckDuckGo-style cards. If the header is Authorization, the key is sent as Bearer.")
        default:
            return AppLocalized("\(Self.label(engine)) is a first-class backend. Paste its API key, then turn on \"Use this engine\". DuckDuckGo stays the no-key fallback when fallback is enabled.")
        }
    }
}

/// One engine's page: "use this engine" + its credential field(s).
private struct WebSearchEngineDetailView: View {
    let engine: WebSearchEngine
    @Binding var selected: WebSearchEngine

    @State private var searxngURL: String = WebSearchSettings.searxngURL
    @State private var apiKey: String = ""
    @State private var customURL: String = WebSearchSettings.customURL
    @State private var customKey: String = WebSearchSettings.customKey
    @State private var customKeyHeader: String = WebSearchSettings.customKeyHeader

    var body: some View {
        Form {
            Section {
                Toggle(AppLocalized("Use this engine"), isOn: Binding(
                    get: { selected == engine },
                    set: { isOn in
                        // Turning it off would leave no engine at all, so the
                        // switch only ever turns one ON (same rule as Android).
                        guard isOn else { return }
                        selected = engine
                        WebSearchSettings.setEngine(engine)
                    }
                ))
            } footer: {
                Text(WebSearchSettingsView.detailFooter(engine))
            }

            Section {
                switch engine {
                case .ddg:
                    EmptyView()
                case .searxng:
                    field(AppLocalized("SearXNG URL"), text: $searxngURL, placeholder: "https://searx.example/search") {
                        WebSearchSettings.searxngURL = $0
                    }
                case .bing, .tavily, .bocha, .exa, .brave, .jina, .zhipu:
                    field(AppLocalized("API key"), text: $apiKey, placeholder: AppLocalized("Paste the provider API key")) {
                        WebSearchSettings.setAPIKey($0, for: engine)
                    }
                case .custom:
                    field(AppLocalized("Search URL"), text: $customURL, placeholder: "https://example.com/search?q={query}") {
                        WebSearchSettings.customURL = $0
                    }
                    field(AppLocalized("API key (optional)"), text: $customKey, placeholder: AppLocalized("Leave blank if unused")) {
                        WebSearchSettings.customKey = $0
                    }
                    field(AppLocalized("API key header (optional)"), text: $customKeyHeader, placeholder: "Authorization") {
                        WebSearchSettings.customKeyHeader = $0
                    }
                }
            } header: {
                Text(AppLocalized("Endpoints"))
            }
        }
        .navigationTitle(WebSearchSettingsView.label(engine))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // Credentials are read once per visit: the key never round-trips
            // through the view while the user types, so a failed save cannot
            // leave a half-typed key in storage.
            apiKey = WebSearchSettings.apiKey(for: engine)
        }
    }

    @ViewBuilder
    private func field(
        _ label: String,
        text: Binding<String>,
        placeholder: String,
        onCommit: @escaping (String) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(placeholder, text: text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(.footnote, design: .monospaced))
                .onSubmit { onCommit(text.wrappedValue) }
                // Persist on every change as well: a user who types a key and
                // leaves the screen by the back gesture never fires onSubmit.
                .onChange(of: text.wrappedValue) { newValue in onCommit(newValue) }
        }
    }
}
