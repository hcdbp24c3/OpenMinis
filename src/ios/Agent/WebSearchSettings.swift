import Foundation

// MARK: - [T-ios-web-search] Engines

/// Search backend for `WebSearchTool`.
///
/// Ported from tall-1997/OpenMinis-Linux (Android, GPL-3 — same licence family
/// as this repo), then extended to Kelivo's full provider set
/// (`lib/core/services/search/providers/`, 26 services) minus the one that only
/// talks to Kelivo's own backend.
///
/// Preference keys are byte-identical to the Android ones, so a settings sync can
/// carry an engine choice and its keys across (the same discipline
/// `AgentToolSwitch` follows).
enum WebSearchEngine: String, CaseIterable {
    case ddg
    case bingLocal = "bing_local"
    case searxng
    case custom
    case tavily
    case bing
    case brave
    case exa
    case jina
    case bocha
    case zhipu
    case kagi
    case linkup
    case metaso
    case ollama
    case parallel
    case perplexity
    case querit
    case serper
    case stepfun
    case tinyfish
    case you
    case firecrawl
    case anysearch
    case doubao
    case kimi
    case grok

    var displayName: String {
        switch self {
        case .ddg: return "DuckDuckGo HTML"
        case .bingLocal: return "Bing (HTML, no key)"
        case .searxng: return "SearXNG"
        case .custom: return "Custom"
        case .tavily: return "Tavily"
        case .bing: return "Bing Web Search API"
        case .brave: return "Brave Search"
        case .exa: return "Exa"
        case .jina: return "Jina Search"
        case .bocha: return "Bocha"
        case .zhipu: return "Zhipu Web Search"
        case .kagi: return "Kagi"
        case .linkup: return "LinkUp"
        case .metaso: return "Metaso"
        case .ollama: return "Ollama Cloud"
        case .parallel: return "Parallel"
        case .perplexity: return "Perplexity"
        case .querit: return "Querit"
        case .serper: return "Serper (Google)"
        case .stepfun: return "StepFun"
        case .tinyfish: return "TinyFish"
        case .you: return "You.com"
        case .firecrawl: return "Firecrawl"
        case .anysearch: return "AnySearch"
        case .doubao: return "Doubao"
        case .kimi: return "Kimi"
        case .grok: return "Grok (xAI)"
        }
    }

    /// `false` = the request carries no credential at all.
    var needsKey: Bool {
        switch self {
        case .ddg, .bingLocal, .searxng, .custom: return false
        default: return true
        }
    }

    var defaultURL: String? {
        switch self {
        case .ddg: return nil
        case .bingLocal: return nil
        case .searxng: return nil
        case .custom: return nil
        case .tavily: return "https://api.tavily.com/search"
        case .bing: return "https://api.bing.microsoft.com/v7.0/search"
        case .brave: return "https://api.search.brave.com/res/v1/web/search"
        case .exa: return "https://api.exa.ai/search"
        case .jina: return "https://s.jina.ai/"
        case .bocha: return "https://api.bochaai.com/v1/web-search"
        case .zhipu: return "https://open.bigmodel.cn/api/paas/v4/web_search"
        case .kagi: return "https://kagi.com/api/v1/search"
        case .linkup: return "https://api.linkup.so/v1/search"
        case .metaso: return "https://metaso.cn/api/v1/search"
        case .ollama: return "https://ollama.com/api/web_search"
        case .parallel: return "https://api.parallel.ai/v1/search"
        case .perplexity: return "https://api.perplexity.ai/search"
        case .querit: return "https://api.querit.ai/v1/search"
        case .serper: return "https://google.serper.dev/search"
        case .stepfun: return "https://api.stepfun.com/v1/search"
        case .tinyfish: return "https://api.search.tinyfish.ai"
        case .you: return "https://ydc-index.io/v1/search"
        case .firecrawl: return "https://api.firecrawl.dev/v2/search"
        case .anysearch: return "https://api.anysearch.com/v1/search"
        case .doubao: return "https://open.feedcoopapi.com/search_api/web_search"
        case .kimi: return "https://api.moonshot.cn/v1/tools/search"
        case .grok: return "https://api.x.ai/v1/responses"
        }
    }

    /// Non-nil when the user may point this engine somewhere else (a self-hosted
    /// gateway, a proxy). Same set of overridable endpoints as Kelivo.
    var urlOverrideKey: String? {
        switch self {
        case .exa: return "exa_url"
        case .jina: return "jina_url"
        case .stepfun: return "stepfun_url"
        case .tinyfish: return "tinyfish_url"
        case .firecrawl: return "firecrawl_url"
        case .anysearch: return "anysearch_url"
        case .grok: return "grok_url"
        default: return nil
        }
    }

    /// Preference key holding this engine's key LIST, e.g. `tavily_keys`.
    var keysKey: String { "\(rawValue)_keys" }
    /// Legacy single-key preference, still read so an existing install keeps working.
    var legacyKeyKey: String { "\(rawValue)_key" }

    static func from(id: String?) -> WebSearchEngine {
        guard let id, let engine = WebSearchEngine(rawValue: id.lowercased()) else { return .ddg }
        return engine
    }
}

// MARK: - [T-ios-web-search] Settings

enum WebSearchSettings {
    static let engineKey = "engine"
    static let searxngURLKey = "searxng_url"
    static let searxngAuthKey = "searxng_auth"
    static let fallbackKey = "fallback_ddg"
    static let customURLKey = "custom_url"
    static let customKeyKey = "custom_key"
    static let customKeyHeaderKey = "custom_key_header"

    private static var defaults: UserDefaults { .standard }

    static var engine: WebSearchEngine {
        if let raw = defaults.string(forKey: engineKey), !raw.isEmpty {
            return WebSearchEngine.from(id: raw)
        }
        // No explicit choice: prefer a backend the user has already configured,
        // else the one that needs nothing.
        return configuredKeyed.first ?? .ddg
    }

    static func setEngine(_ engine: WebSearchEngine) {
        defaults.set(engine.rawValue, forKey: engineKey)
    }

    /// Keyed backends the user has already filled in, in enum order.
    static var configuredKeyed: [WebSearchEngine] {
        WebSearchEngine.allCases.filter { $0.needsKey && !keys(for: $0).isEmpty }
    }

    // ── keys ────────────────────────────────────────────────────────────────

    /// This engine's keys, in rotation order. Falls back to the legacy single-key
    /// preference, so an install configured before multiple keys existed keeps it.
    static func keys(for engine: WebSearchEngine) -> [String] {
        if let stored = defaults.string(forKey: engine.keysKey), !stored.isEmpty {
            let parsed = parseKeyBatch(stored)
            if !parsed.isEmpty { return parsed }
        }
        guard let legacy = defaults.string(forKey: engine.legacyKeyKey), !legacy.isEmpty else { return [] }
        return parseKeyBatch(legacy)
    }

    static func setKeys(_ raw: String, for engine: WebSearchEngine) {
        let parsed = parseKeyBatch(raw)
        defaults.set(parsed.joined(separator: "\n"), forKey: engine.keysKey)
        // The legacy slot is cleared once a list exists, so the two cannot
        // disagree about what is configured.
        defaults.removeObject(forKey: engine.legacyKeyKey)
    }

    /// Split a pasted batch. Separators mirror Kelivo's key rotator: newlines,
    /// commas, semicolons or whitespace, so a pasted spreadsheet column works.
    static func parseKeyBatch(_ input: String) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for part in input.split(whereSeparator: { $0.isWhitespace || $0 == "," || $0 == ";" }) {
            let key = String(part).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, seen.insert(key).inserted else { continue }
            out.append(key)
        }
        return out
    }

    /// `abcd••••wxyz` — enough to tell two keys apart without showing either.
    static func maskKey(_ key: String) -> String {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > 8 else { return "••••••••" }
        return "\(trimmed.prefix(4))••••\(trimmed.suffix(4))"
    }

    // ── endpoints ───────────────────────────────────────────────────────────

    static func urlOverride(for engine: WebSearchEngine) -> String {
        guard let key = engine.urlOverrideKey else { return "" }
        return (defaults.string(forKey: key) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func setURLOverride(_ value: String, for engine: WebSearchEngine) {
        guard let key = engine.urlOverrideKey else { return }
        defaults.set(value.trimmingCharacters(in: .whitespacesAndNewlines), forKey: key)
    }

    /// Endpoint in use: the user's override when set, else the documented default.
    static func resolvedURL(for engine: WebSearchEngine) -> String {
        let override = urlOverride(for: engine)
        if !override.isEmpty { return override }
        return engine.defaultURL ?? ""
    }

    static var searxngURL: String {
        get { (defaults.string(forKey: searxngURLKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: searxngURLKey) }
    }

    /// `user:password` for instances behind Basic auth; blank = anonymous.
    static var searxngAuth: String {
        get { (defaults.string(forKey: searxngAuthKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: searxngAuthKey) }
    }

    static var customURL: String {
        get { (defaults.string(forKey: customURLKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: customURLKey) }
    }

    static var customKey: String {
        get { (defaults.string(forKey: customKeyKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: customKeyKey) }
    }

    static var customKeyHeader: String {
        get { (defaults.string(forKey: customKeyHeaderKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: customKeyHeaderKey) }
    }

    static var fallbackEnabled: Bool {
        get { defaults.object(forKey: fallbackKey) as? Bool ?? true }
        set { defaults.set(newValue, forKey: fallbackKey) }
    }
}

/// [T-ios-web-search] Round-robin over an engine's key list.
///
/// In memory on purpose, exactly like Kelivo's `SearchApiKeyRotator`: the cursor
/// spreads load within a run and is not user state worth persisting — a persisted
/// cursor can park a bad key at the head of the rotation after a restart. A
/// single-key pool never advances, so the common case behaves as it always did.
enum SearchKeyRotator {
    private static var cursors: [String: Int] = [:]
    private static let lock = NSLock()

    static func next(engineID: String, pool: [String]) -> String {
        if pool.isEmpty { return "" }
        if pool.count == 1 { return pool[0] }
        lock.lock()
        defer { lock.unlock() }
        let index = (cursors[engineID] ?? 0) % pool.count
        cursors[engineID] = (index + 1) % pool.count
        return pool[index]
    }

    static func select(_ engine: WebSearchEngine) -> String {
        next(engineID: engine.rawValue, pool: WebSearchSettings.keys(for: engine))
    }

    /// Test seam.
    static func reset() {
        lock.lock()
        defer { lock.unlock() }
        cursors.removeAll()
    }
}
