import SwiftUI

// MARK: - [T-ios-web-search] Settings › Tools › Web search

/// Which backend `web_search` uses, and its credentials. Android twin:
/// `ui/settings/WebSearchSettingsScreen.kt` — same engines, same fields, same
/// rules, so the two clients describe one contract to the user.
///
/// Engine NAMES come from `WebSearchEngine.displayName` rather than one localized
/// string per provider: they are product names ("Tavily", "Kagi", "You.com"), so
/// translating them would be dozens of keys of nothing in every language.
///
/// Keyed backends take a BATCH of keys — one per line, or pasted from a column —
/// and `SearchKeyRotator` rotates them per request. The list under the field shows
/// each key masked, so a user can see how many are configured without the screen
/// displaying any of them.
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
                                Text(item.displayName)
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
                Text(AppLocalized("Tap an engine to configure it. DuckDuckGo and Bing's HTML page need no key. SearXNG needs an instance URL. The rest need an API key — paste several, one per line, and they are rotated per request. Failed searches fall back to DuckDuckGo, then suggest browser_use."))
            }

            Section {
                Toggle(AppLocalized("Fallback to DuckDuckGo"), isOn: $fallback)
                    // iOS 16 form (the two-parameter closure is iOS 17+), which is
                    // what every other call site in this repo uses.
                    .onChange(of: fallback) { newValue in
                        WebSearchSettings.fallbackEnabled = newValue
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

    /// Configured / how many keys, under each engine row.
    private func subtitle(for item: WebSearchEngine) -> String {
        switch item {
        case .ddg, .bingLocal:
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
            let count = WebSearchSettings.keys(for: item).count
            if count == 0 { return AppLocalized("Not configured") }
            if count == 1 { return AppLocalized("Configured") }
            // Multiple keys are the point of the rotation: say how many rather
            // than hiding the difference behind "Configured".
            return AppLocalized("\(count) keys configured")
        }
    }

    static func detailFooter(_ engine: WebSearchEngine) -> String {
        switch engine {
        case .ddg:
            return AppLocalized("HTML search with no credentials. Default engine.")
        case .bingLocal:
            return AppLocalized("Bing public HTML results page — no API key. The paid Web Search API is a separate engine in the list.")
        case .searxng:
            return AppLocalized("Public or self-hosted SearXNG instance. Paste the search endpoint or site origin; add user:password if the instance needs Basic auth.")
        case .custom:
            return AppLocalized("Use {query} (and optional {key}) in the URL. JSON arrays named results/items/data are parsed; HTML falls back to DuckDuckGo-style cards. If the header is Authorization, the key is sent as Bearer.")
        default:
            return AppLocalized("\(engine.displayName) is a first-class backend. Paste its API key (several keys, one per line, are rotated per request), then turn on \"Use this engine\". DuckDuckGo stays the no-key fallback when fallback is enabled.")
        }
    }
}

/// One engine's page: "use this engine", its keys (batch), and — where the
/// provider allows it — an endpoint override.
private struct WebSearchEngineDetailView: View {
    let engine: WebSearchEngine
    @Binding var selected: WebSearchEngine

    @State private var keysText: String = ""
    @State private var searxngURL: String = WebSearchSettings.searxngURL
    @State private var searxngAuth: String = WebSearchSettings.searxngAuth
    @State private var customURL: String = WebSearchSettings.customURL
    @State private var customKey: String = WebSearchSettings.customKey
    @State private var customKeyHeader: String = WebSearchSettings.customKeyHeader
    @State private var urlOverride: String = ""

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

            if engine.needsKey {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(AppLocalized("API keys (one per line — they rotate)"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextEditor(text: $keysText)
                            .font(.system(.footnote, design: .monospaced))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .frame(minHeight: 80)
                            .onChange(of: keysText) { newValue in
                                WebSearchSettings.setKeys(newValue, for: engine)
                            }
                    }
                    let stored = WebSearchSettings.parseKeyBatch(keysText)
                    if stored.count > 1 {
                        Text(AppLocalized("\(stored.count) keys — rotated per request"))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(stored, id: \.self) { key in
                        Text(WebSearchSettings.maskKey(key))
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(AppLocalized("Credentials"))
                }
            }

            switch engine {
            case .searxng:
                Section {
                    field(AppLocalized("SearXNG URL"), text: $searxngURL, placeholder: "https://searx.example/search") {
                        WebSearchSettings.searxngURL = $0
                    }
                    field(AppLocalized("Basic auth (optional)"), text: $searxngAuth, placeholder: "user:password") {
                        WebSearchSettings.searxngAuth = $0
                    }
                } header: {
                    Text(AppLocalized("Endpoints"))
                }
            case .custom:
                Section {
                    field(AppLocalized("Search URL"), text: $customURL, placeholder: "https://example.com/search?q={query}") {
                        WebSearchSettings.customURL = $0
                    }
                    field(AppLocalized("API key (optional)"), text: $customKey, placeholder: AppLocalized("Leave blank if unused")) {
                        WebSearchSettings.customKey = $0
                    }
                    field(AppLocalized("API key header (optional)"), text: $customKeyHeader, placeholder: "Authorization") {
                        WebSearchSettings.customKeyHeader = $0
                    }
                } header: {
                    Text(AppLocalized("Endpoints"))
                }
            default:
                if engine.urlOverrideKey != nil {
                    Section {
                        field(AppLocalized("Endpoint URL (optional)"), text: $urlOverride, placeholder: engine.defaultURL ?? "") {
                            WebSearchSettings.setURLOverride($0, for: engine)
                        }
                    } header: {
                        Text(AppLocalized("Endpoints"))
                    } footer: {
                        Text(AppLocalized("Blank uses the documented endpoint for this provider. Set it to point at a self-hosted gateway or proxy."))
                    }
                }
            }
        }
        .navigationTitle(engine.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // Credentials and overrides are read once per visit; the key list is
            // written on every change so a failed save cannot leave a half-typed
            // key behind.
            keysText = WebSearchSettings.keys(for: engine).joined(separator: "\n")
            urlOverride = WebSearchSettings.urlOverride(for: engine)
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
                // Persist on every change as well: a user who types a value and
                // leaves by the back gesture never fires onSubmit.
                .onChange(of: text.wrappedValue) { newValue in onCommit(newValue) }
        }
    }
}
