import Foundation

// MARK: - [T-ios-web-search] Engine selection

/// User-configurable search backend for `WebSearchTool`.
///
/// Ported from tall-1997/OpenMinis-Linux (Android, GPL-3 — same licence family
/// as this repo), which itself follows Kelivo's multi-backend design. The
/// UserDefaults key strings are byte-identical to the Android ones so a future
/// settings sync can carry a user's choice across platforms unchanged, the same
/// discipline `AgentToolSwitch` already follows.
///
/// DuckDuckGo stays the default because it needs no key: the tool works on a
/// fresh install, and every keyed backend is opt-in.
enum WebSearchEngine: String, CaseIterable {
    case ddg
    case searxng
    case bing
    case tavily
    case bocha
    case exa
    case brave
    case jina
    case zhipu
    case custom

    var needsKey: Bool {
        switch self {
        case .ddg, .searxng, .custom: return false
        case .bing, .tavily, .bocha, .exa, .brave, .jina, .zhipu: return true
        }
    }
}

/// UserDefaults-backed settings for `WebSearchTool`.
enum WebSearchSettings {
    static let engineKey = "engine"
    static let searxngURLKey = "searxng_url"
    static let fallbackKey = "fallback_ddg"
    static let customURLKey = "custom_url"
    static let customKeyKey = "custom_key"
    static let customKeyHeaderKey = "custom_key_header"

    /// Per-engine credential keys. Same spelling as Android's
    /// `WebSearchSettings.KEY_*`, one key per engine rather than a dictionary so
    /// an older build cannot drop an unknown engine's credential on rewrite.
    static func credentialKey(_ engine: WebSearchEngine) -> String {
        switch engine {
        case .bing: return "bing_key"
        case .tavily: return "tavily_key"
        case .bocha: return "bocha_key"
        case .exa: return "exa_key"
        case .brave: return "brave_key"
        case .jina: return "jina_key"
        case .zhipu: return "zhipu_key"
        case .ddg, .searxng, .custom: return ""
        }
    }

    private static var defaults: UserDefaults { .standard }

    static var engine: WebSearchEngine {
        if let raw = defaults.string(forKey: engineKey),
           let parsed = WebSearchEngine(rawValue: raw) {
            return parsed
        }
        // No explicit choice: prefer a backend the user has already configured,
        // else DuckDuckGo. Mirrors Android's `firstConfigured() ?: DDG`.
        return configuredKeyed.first ?? .ddg
    }

    static func setEngine(_ engine: WebSearchEngine) {
        defaults.set(engine.rawValue, forKey: engineKey)
    }

    /// Keyed backends the user has already filled in, in enum order.
    static var configuredKeyed: [WebSearchEngine] {
        WebSearchEngine.allCases.filter { $0.needsKey && !apiKey(for: $0).isEmpty }
    }

    static func apiKey(for engine: WebSearchEngine) -> String {
        let key = credentialKey(engine)
        guard !key.isEmpty else { return "" }
        return (defaults.string(forKey: key) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func setAPIKey(_ value: String, for engine: WebSearchEngine) {
        let key = credentialKey(engine)
        guard !key.isEmpty else { return }
        defaults.set(value.trimmingCharacters(in: .whitespacesAndNewlines), forKey: key)
    }

    static var searxngURL: String {
        get { (defaults.string(forKey: searxngURLKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: searxngURLKey) }
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

    /// Retry DuckDuckGo after the selected engine fails. Default ON, as on Android.
    static var fallbackEnabled: Bool {
        defaults.object(forKey: fallbackKey) as? Bool ?? true
    }

    static func setFallbackEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: fallbackKey)
    }
}

// MARK: - [T-ios-web-search] The tool

/// Search the public web and return titles, URLs and snippets.
///
/// Android twin: `tools/WebSearchTool.kt` — same engines, same request shapes,
/// same parsing rules, same no-key fallbacks. Kept in step deliberately: a
/// vendor that answers one platform and 400s the other is the failure mode this
/// pairing exists to prevent, and the parsers are pinned by tests on both sides.
///
/// The point of the tool is cost: looking up a fact used to mean `browser_use` —
/// a real WebView, a page load, a screenshot round-trip. This is one HTTP
/// request that returns text the model can read directly.
enum WebSearchTool {
    static let name = "web_search"
    static let maxResults = 8
    private static let timeout: TimeInterval = 15

    struct Result: Equatable {
        let title: String
        let url: String
        let snippet: String
    }

    /// What the dispatcher needs: the text for the transcript, and whether the
    /// call succeeded. Mirrors Android's `ToolExecutionResult` fields it uses.
    struct Execution {
        let output: String
        let success: Bool
    }

    static func definition() -> AgentToolDefinition {
        AgentToolDefinition(
            name: name,
            description: "Search the public web and return titles, URLs, and snippets. " +
                "Use this for facts, docs, news, and package versions instead of opening a browser. " +
                "Follow up with browser_use only when you need to interact with a specific page. " +
                "Uses Settings → Web search. First-class backends: Tavily, Bocha, Exa, Brave, Jina, Zhipu, Bing, SearXNG. " +
                "Falls back to DuckDuckGo, then suggest browser_use for a specific URL.",
            parameters: [
                "tool_title": AgentToolParam(
                    type: .string,
                    description: "A concise 5-10 word summary shown to the user (e.g. 'Search Android 15 release notes'). Use the same language as the user."
                ),
                "query": AgentToolParam(
                    type: .string,
                    description: "Search query. Be specific; include version numbers or site: filters when useful."
                ),
                "max_results": AgentToolParam(
                    type: .integer,
                    description: "How many results to return (default 5, max 8)."
                ),
            ],
            required: ["tool_title", "query"],
            propertyOrdering: ["tool_title", "query", "max_results"]
        )
    }

    static func execute(argsJSON: String) async -> Execution {
        guard let data = argsJSON.data(using: .utf8),
              let args = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return Execution(output: "web_search failed: arguments were not a JSON object", success: false)
        }
        let query = (args["query"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            return Execution(output: "query is required", success: false)
        }
        let requested = (args["max_results"] as? Int) ?? Int(args["max_results"] as? String ?? "") ?? 5
        let max = min(max(requested, 1), maxResults)

        let preferred = WebSearchSettings.engine
        var engines: [WebSearchEngine] = [preferred]
        // A user who picked DuckDuckGo must not silently spend keyed quotas when
        // it returns nothing: keyed fallback only runs after a KEYED engine was
        // the one they asked for.
        if WebSearchSettings.fallbackEnabled, preferred != .ddg {
            engines += WebSearchSettings.configuredKeyed.filter { $0 != preferred }
            if !engines.contains(.ddg) { engines.append(.ddg) }
        }

        var lastError: String?
        var used = preferred
        var results: [Result] = []
        for engine in engines {
            used = engine
            let attempt = await search(engine: engine, query: query, max: max)
            if !attempt.results.isEmpty {
                results = attempt.results
                lastError = nil
                break
            }
            lastError = attempt.error
        }

        if results.isEmpty {
            let fallback = await searchNoKeyFallback(query: query, max: max)
            if !fallback.isEmpty {
                return Execution(output: format(query: query, results: fallback, engine: "no-key-fallback"), success: true)
            }
            let reason = lastError ?? "no results"
            return Execution(
                output: "web_search failed for \"\(query)\" via \(preferred.rawValue): \(reason). " +
                    "DuckDuckGo HTML returned no cards, and the no-key Wikipedia/Bing/Mojeek fallback was also empty. " +
                    "Configure Settings → Web search, or open a known URL with browser_use.",
                success: false
            )
        }
        return Execution(output: format(query: query, results: results, engine: used.rawValue), success: true)
    }

    // MARK: - Engines

    private struct Attempt {
        let results: [Result]
        let error: String?
    }

    private static func search(engine: WebSearchEngine, query: String, max: Int) async -> Attempt {
        switch engine {
        case .ddg:
            return await searchDuckDuckGo(query: query, max: max)

        case .searxng:
            let base = WebSearchSettings.searxngURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !base.isEmpty else { return Attempt(results: [], error: "SearXNG URL is not configured") }
            let endpoint = base.hasSuffix("/search") ? base : "\(base)/search"
            guard let body = await fetch("\(endpoint)?q=\(urlEncode(query))&format=json") else {
                return Attempt(results: [], error: "empty response from SearXNG")
            }
            let parsed = parseSearxJSON(body, max: max)
            return Attempt(results: parsed, error: parsed.isEmpty ? "SearXNG returned no results" : nil)

        case .bing:
            return await keyedGet(
                engine: engine,
                url: "https://api.bing.microsoft.com/v7.0/search?q=\(urlEncode(query))&count=\(max)",
                headerName: "Ocp-Apim-Subscription-Key",
                max: max
            ) { parseBingJSON($0, max: max) }

        case .tavily:
            return await keyedPost(
                engine: engine,
                url: "https://api.tavily.com/search",
                body: ["query": query, "max_results": max, "search_depth": "basic"],
                keyField: "api_key",
                headerName: nil,
                bearer: false,
                max: max
            )

        case .bocha:
            return await keyedPost(
                engine: engine,
                url: "https://api.bochaai.com/v1/web-search",
                body: ["query": query, "count": max, "summary": true],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .exa:
            return await keyedPost(
                engine: engine,
                url: "https://api.exa.ai/search",
                body: ["query": query, "numResults": max, "contents": ["text": ["maxCharacters": 400]]],
                keyField: nil,
                headerName: "x-api-key",
                bearer: false,
                max: max
            )

        case .brave:
            return await keyedGet(
                engine: engine,
                url: "https://api.search.brave.com/res/v1/web/search?q=\(urlEncode(query))&count=\(max)",
                headerName: "X-Subscription-Token",
                max: max
            ) { parseGenericSearchJSON($0, max: max) }

        case .jina:
            return await keyedGet(
                engine: engine,
                url: "https://s.jina.ai/\(urlEncode(query))",
                headerName: "Authorization",
                bearer: true,
                extra: ["Accept": "application/json"],
                max: max
            ) { parseGenericSearchJSON($0, max: max) }

        case .zhipu:
            return await keyedPost(
                engine: engine,
                url: "https://open.bigmodel.cn/api/paas/v4/web_search",
                body: ["search_query": query, "count": max],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .custom:
            let template = WebSearchSettings.customURL
            guard !template.isEmpty else { return Attempt(results: [], error: "Custom search URL is not configured") }
            let key = WebSearchSettings.customKey
            let headerName = WebSearchSettings.customKeyHeader
            let endpoint = expandCustomURL(template: template, query: query, key: key)
            var headers = ["Accept": "application/json, text/html"]
            if !key.isEmpty, !template.lowercased().contains("{key}") {
                let header = headerName.isEmpty ? "Authorization" : headerName
                if header.lowercased() == "authorization", !key.lowercased().hasPrefix("bearer ") {
                    headers[header] = "Bearer \(key)"
                } else {
                    headers[header] = key
                }
            }
            guard let body = await fetch(endpoint, extraHeaders: headers) else {
                return Attempt(results: [], error: "empty response from custom search")
            }
            let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
            let parsed = trimmed.hasPrefix("{") || trimmed.hasPrefix("[")
                ? parseGenericSearchJSON(body, max: max)
                : parseHTML(body, max: max)
            return Attempt(results: parsed, error: parsed.isEmpty ? "Custom search returned no results" : nil)
        }
    }

    private static func keyedGet(
        engine: WebSearchEngine,
        url: String,
        headerName: String,
        bearer: Bool = false,
        extra: [String: String] = [:],
        max: Int,
        parse: (String) -> [Result]
    ) async -> Attempt {
        let key = WebSearchSettings.apiKey(for: engine)
        guard !key.isEmpty else { return Attempt(results: [], error: "\(engine.rawValue) API key is not configured") }
        var headers = ["Accept": "application/json"]
        headers.merge(extra) { _, new in new }
        headers[headerName] = bearer && !key.lowercased().hasPrefix("bearer ") ? "Bearer \(key)" : key
        guard let body = await fetch(url, extraHeaders: headers) else {
            return Attempt(results: [], error: "empty response from \(engine.rawValue)")
        }
        let parsed = parse(body)
        return Attempt(results: parsed, error: parsed.isEmpty ? "\(engine.rawValue) returned no results" : nil)
    }

    private static func keyedPost(
        engine: WebSearchEngine,
        url: String,
        body: [String: Any],
        keyField: String?,
        headerName: String?,
        bearer: Bool,
        max: Int
    ) async -> Attempt {
        let key = WebSearchSettings.apiKey(for: engine)
        guard !key.isEmpty else { return Attempt(results: [], error: "\(engine.rawValue) API key is not configured") }
        var payload = body
        if let keyField { payload[keyField] = key }
        var headers = ["Accept": "application/json", "Content-Type": "application/json"]
        if let headerName { headers[headerName] = key }
        if bearer { headers["Authorization"] = key.lowercased().hasPrefix("bearer ") ? key : "Bearer \(key)" }
        guard let raw = await postJSON(url, json: payload, extraHeaders: headers) else {
            return Attempt(results: [], error: "empty response from \(engine.rawValue)")
        }
        let parsed = parseGenericSearchJSON(raw, max: max)
        return Attempt(results: parsed, error: parsed.isEmpty ? "\(engine.rawValue) returned no results" : nil)
    }

    // MARK: - DuckDuckGo (no key)

    private static func searchDuckDuckGo(query: String, max: Int) async -> Attempt {
        let urls = [
            "https://html.duckduckgo.com/html/?q=\(urlEncode(query))",
            "https://lite.duckduckgo.com/lite/?q=\(urlEncode(query))",
        ]
        var last = "DuckDuckGo returned no cards"
        var sawBody = false
        for url in urls {
            guard let html = await fetch(url) else { continue }
            sawBody = true
            if html.contains("anomaly.js") || html.contains("Unfortunately, bots use DuckDuckGo") {
                last = "DuckDuckGo blocked this client. Set a backend in Settings → Web search."
                continue
            }
            let parsed = parseHTML(html, max: max)
            if !parsed.isEmpty { return Attempt(results: parsed, error: nil) }
        }
        if !sawBody { last = "empty response from DuckDuckGo" }
        return Attempt(results: [], error: last)
    }

    // MARK: - Parsers

    static func parseSearxJSON(_ json: String, max: Int = maxResults) -> [Result] {
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = root["results"] as? [[String: Any]] else { return [] }
        var out: [Result] = []
        for item in arr {
            let url = (item["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let title = (item["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty, !title.isEmpty else { continue }
            out.append(Result(title: title, url: url, snippet: (item["content"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)))
            if out.count >= max { break }
        }
        return out
    }

    static func parseBingJSON(_ json: String, max: Int = maxResults) -> [Result] {
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let pages = root["webPages"] as? [String: Any],
              let arr = pages["value"] as? [[String: Any]] else { return [] }
        var out: [Result] = []
        for item in arr {
            let url = (item["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let title = (item["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty, !title.isEmpty else { continue }
            out.append(Result(title: title, url: url, snippet: (item["snippet"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)))
            if out.count >= max { break }
        }
        return out
    }

    /// `{query}` / `{q}` / `{key}` template expansion. When the template names no
    /// query placeholder the query is appended as `q=`, which is what most
    /// self-hosted search endpoints accept.
    static func expandCustomURL(template: String, query: String, key: String = "") -> String {
        let q = urlEncode(query)
        let k = urlEncode(key)
        var url = template.trimmingCharacters(in: .whitespacesAndNewlines)
        for placeholder in ["{query}", "{q}"] {
            url = url.replacingOccurrences(of: placeholder, with: q, options: .caseInsensitive)
        }
        url = url.replacingOccurrences(of: "{key}", with: k, options: .caseInsensitive)
        let lowered = template.lowercased()
        if !lowered.contains("{query}"), !lowered.contains("{q}") {
            let separator = url.contains("?") ? "&" : "?"
            url += "\(separator)q=\(q)"
        }
        return url
    }

    static func parseGenericSearchJSON(_ json: String, max: Int) -> [Result] {
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("["),
           let data = trimmed.data(using: .utf8),
           let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] {
            return parseResultArray(arr, max: max)
        }
        let fromSearx = parseSearxJSON(json, max: max)
        if !fromSearx.isEmpty { return fromSearx }
        let fromBing = parseBingJSON(json, max: max)
        if !fromBing.isEmpty { return fromBing }
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        return findResultArray(node: root, max: max, depth: 0) ?? []
    }

    /// Keys that hold hit arrays in the wild. Bocha nests hits at
    /// `data.webPages.value`, which a single flat pass misses.
    private static let resultKeys = [
        "results", "items", "data", "organic", "organic_results",
        "webPages", "web", "search_result", "value",
    ]

    private static func findResultArray(node: [String: Any], max: Int, depth: Int) -> [Result]? {
        if depth > 3 { return nil }
        for key in resultKeys {
            if let arr = node[key] as? [[String: Any]] {
                let parsed = parseResultArray(arr, max: max)
                if !parsed.isEmpty { return parsed }
            }
        }
        if depth == 3 { return nil }
        for key in resultKeys {
            guard let child = node[key] as? [String: Any] else { continue }
            if let found = findResultArray(node: child, max: max, depth: depth + 1) { return found }
        }
        return nil
    }

    private static func parseResultArray(_ arr: [[String: Any]], max: Int) -> [Result] {
        var out: [Result] = []
        for item in arr {
            let url = firstNonEmpty(item, ["url", "link", "href", "displayUrl"])
            let title = firstNonEmpty(item, ["title", "name"])
            guard !url.isEmpty, !title.isEmpty else { continue }
            let snippet = firstNonEmpty(item, ["snippet", "content", "description", "summary", "text"])
            out.append(Result(title: title, url: url, snippet: snippet))
            if out.count >= max { break }
        }
        return out
    }

    private static func firstNonEmpty(_ item: [String: Any], _ keys: [String]) -> String {
        for key in keys {
            let value = (item[key] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty { return value }
        }
        return ""
    }

    /// DuckDuckGo's HTML and Lite endpoints, plus the generic `<a>` rows the
    /// Lite page uses. Regexes are the Android twins', kept identical so a
    /// markup change breaks both platforms at once instead of one silently.
    static func parseHTML(_ html: String, max: Int = maxResults) -> [Result] {
        var out: [Result] = []
        var seen = Set<String>()

        let cardPattern = "class=\"result(?:__body)?\"[\\s\\S]{0,2500}?class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]{0,1200}?class=\"result__snippet\"[^>]*>([\\s\\S]*?)</(?:a|td|div)>"
        for match in matches(cardPattern, in: html) where match.count >= 4 {
            let url = decodeDuckLink(htmlUnescape(match[1]))
            let title = stripTags(match[2])
            let snippet = stripTags(match[3])
            guard !url.isEmpty, !title.isEmpty, seen.insert(url).inserted else { continue }
            out.append(Result(title: title, url: url, snippet: snippet))
            if out.count >= max { return out }
        }
        if !out.isEmpty { return out }

        let litePattern = "<a[^>]+rel=\"nofollow\"[^>]+href=\"(https?://[^\"]+)\"[^>]*>([\\s\\S]*?)</a>"
        for match in matches(litePattern, in: html) where match.count >= 3 {
            let url = htmlUnescape(match[1])
            if url.lowercased().contains("duckduckgo.com") { continue }
            let title = stripTags(match[2])
            guard !url.isEmpty, !title.isEmpty, seen.insert(url).inserted else { continue }
            out.append(Result(title: title, url: url, snippet: ""))
            if out.count >= max { break }
        }
        return out
    }

    static func decodeDuckLink(_ raw: String) -> String {
        let href = htmlUnescape(raw).trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized: String
        if href.hasPrefix("//") {
            normalized = "https:" + href
        } else if href.hasPrefix("/l/?") {
            normalized = "https://duckduckgo.com" + href
        } else {
            normalized = href
        }
        guard let markerRange = normalized.range(of: "uddg=") else { return normalized }
        let rest = normalized[markerRange.upperBound...]
        // Substring has no `removingPercentEncoding`; materialize first.
        let encoded = String(rest.prefix { $0 != "&" })
        guard !encoded.isEmpty else { return normalized }
        return encoded.removingPercentEncoding ?? encoded
    }

    // MARK: - No-key fallbacks

    /// DuckDuckGo's HTML page often returns no result cards (bot wall, layout
    /// change). Wikipedia OpenSearch and public HTML engines need no API key, so
    /// they run only after every configured engine returned empty.
    private static func searchNoKeyFallback(query: String, max: Int) async -> [Result] {
        var out: [Result] = []
        out += await searchWikipedia(query: query, max: max)
        if out.count < max {
            out += await searchHTMLLinks(
                url: "https://www.bing.com/search?q=\(urlEncode(query))&setlang=zh-Hans",
                max: max - out.count,
                pattern: "<h2>\\s*<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>"
            )
        }
        if out.count < max {
            out += await searchHTMLLinks(
                url: "https://www.mojeek.com/search?q=\(urlEncode(query))",
                max: max - out.count,
                pattern: "<a[^>]+class=\"[^\"]*title[^\"]*\"[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>"
            )
        }
        var seen = Set<String>()
        return out.filter { seen.insert($0.url).inserted }.prefix(max).map { $0 }
    }

    private static func searchWikipedia(query: String, max: Int) async -> [Result] {
        // CJK queries hit the zh wiki: the en wiki returns nothing for them.
        let host = query.unicodeScalars.contains { $0.value > 127 } ? "zh.wikipedia.org" : "en.wikipedia.org"
        let url = "https://\(host)/w/api.php?action=opensearch&search=\(urlEncode(query))&limit=\(max)&namespace=0&format=json"
        guard let body = await fetch(url, extraHeaders: ["Accept": "application/json"]),
              let data = body.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [Any],
              arr.count >= 4,
              let titles = arr[1] as? [String],
              let urls = arr[3] as? [String] else { return [] }
        let snippets = (arr[2] as? [String]) ?? []
        var out: [Result] = []
        for i in 0..<min(titles.count, max) where i < urls.count {
            let link = urls[i]
            guard link.hasPrefix("http") else { continue }
            out.append(Result(title: titles[i], url: link, snippet: i < snippets.count ? snippets[i] : ""))
        }
        return out
    }

    private static func searchHTMLLinks(url: String, max: Int, pattern: String) async -> [Result] {
        guard max > 0, let html = await fetch(url) else { return [] }
        var out: [Result] = []
        for match in matches(pattern, in: html) where match.count >= 3 {
            let link = match[1].trimmingCharacters(in: .whitespacesAndNewlines)
            guard link.hasPrefix("http"), !link.contains("bing.com/ck/"), !link.contains("microsoft.com") else { continue }
            let title = stripTags(match[2])
            guard !link.isEmpty, !title.isEmpty else { continue }
            out.append(Result(title: title, url: link, snippet: ""))
            if out.count >= max { break }
        }
        return out
    }

    // MARK: - Formatting helpers

    private static func format(query: String, results: [Result], engine: String = "ddg") -> String {
        var lines: [String] = ["web_search (\(engine)) results for \"\(query)\" (\(results.count)):"]
        for (index, r) in results.enumerated() {
            lines.append("")
            lines.append("\(index + 1). \(r.title)")
            lines.append("   \(r.url)")
            if !r.snippet.isEmpty { lines.append("   \(r.snippet)") }
        }
        return lines.joined(separator: "\n")
    }

    /// RFC 3986 unreserved set: `.urlQueryAllowed` leaves `&` and `+` alone,
    /// which would split a query into extra parameters at the endpoint.
    static func urlEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private static func stripTags(_ raw: String) -> String {
        let withoutTags = raw.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        return htmlUnescape(withoutTags)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func htmlUnescape(_ raw: String) -> String {
        raw.replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&nbsp;", with: " ")
    }

    /// Group 0 is the whole match, so callers index matches from 1.
    private static func matches(_ pattern: String, in text: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, options: [], range: range).map { match in
            (0..<match.numberOfRanges).map { index in
                guard let r = Range(match.range(at: index), in: text) else { return "" }
                return String(text[r])
            }
        }
    }

    // MARK: - HTTP

    /// Same mobile-Chrome fingerprint the Android tool sends (its
    /// `UserAgentProfile.MOBILE_CHROME`), because these HTML endpoints answer
    /// bots by UA: one platform sending a bot-looking string and the other a
    /// browser one is how "works on Android, blocked on iOS" happens.
    private static let userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private static func fetch(_ urlString: String, extraHeaders: [String: String] = [:]) async -> String? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(extraHeaders["Accept"] ?? "text/html,application/xhtml+xml,application/json", forHTTPHeaderField: "Accept")
        request.setValue("zh-CN,zh;q=0.9,en;q=0.8", forHTTPHeaderField: "Accept-Language")
        for (key, value) in extraHeaders { request.setValue(value, forHTTPHeaderField: key) }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1)
        } catch {
            return nil
        }
    }

    private static func postJSON(_ urlString: String, json: [String: Any], extraHeaders: [String: String] = [:]) async -> String? {
        guard let url = URL(string: urlString),
              let body = try? JSONSerialization.data(withJSONObject: json) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.timeoutInterval = timeout
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for (key, value) in extraHeaders { request.setValue(value, forHTTPHeaderField: key) }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1)
        } catch {
            return nil
        }
    }
}
