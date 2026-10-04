import Foundation

// MARK: - [T-ios-web-search] The tool

/// Search the public web and return titles, URLs and snippets.
///
/// Android twin: `tools/WebSearchTool.kt` — same engine table, same request
/// shapes, same parsers, same fallbacks, kept in step deliberately: a vendor that
/// answers one client and rejects the other is the failure this pairing exists to
/// prevent, and the parsers are pinned by tests on the Android side.
///
/// The point of the tool is cost: looking up a fact used to mean `browser_use` —
/// a real WebView, a page load, a screenshot round-trip. This is one HTTP request
/// that returns text the model can read directly.
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
    /// call succeeded.
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
        // A user who picked a no-key engine must not silently spend keyed quotas
        // when it returns nothing: keyed fallback only runs after a KEYED engine
        // was the one they asked for.
        if WebSearchSettings.fallbackEnabled, preferred.needsKey {
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

    /// Same bodies, headers and parse routes as the Android switch. Kept in the
    /// same ORDER as `WebSearchEngine` so the two files can be diffed.
    private static func search(engine: WebSearchEngine, query: String, max: Int) async -> Attempt {
        switch engine {
        case .ddg:
            return await searchDuckDuckGo(query: query, max: max)

        // Bing's public HTML endpoint: no key, and the reason "Bing is free".
        // The paid Web Search API is a separate engine for users who have one.
        case .bingLocal:
            guard max > 0 else { return Attempt(results: [], error: nil) }
            guard let html = await fetch("https://www.bing.com/search?q=\(urlEncode(query))&setlang=zh-Hans") else {
                return Attempt(results: [], error: "empty response from Bing HTML")
            }
            let parsed = parseHTMLLinks(
                html,
                max: max,
                pattern: "<h2>\\s*<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>"
            )
            return Attempt(results: parsed, error: parsed.isEmpty ? "Bing HTML returned no results" : nil)

        case .searxng:
            let base = WebSearchSettings.searxngURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !base.isEmpty else { return Attempt(results: [], error: "SearXNG URL is not configured") }
            let endpoint = base.hasSuffix("/search") ? base : "\(base)/search"
            var headers: [String: String] = [:]
            let auth = WebSearchSettings.searxngAuth
            if !auth.isEmpty, let token = auth.data(using: .utf8)?.base64EncodedString() {
                headers["Authorization"] = "Basic \(token)"
            }
            guard let body = await fetch("\(endpoint)?q=\(urlEncode(query))&format=json", extraHeaders: headers) else {
                return Attempt(results: [], error: "empty response from SearXNG")
            }
            let parsed = parseSearxJSON(body, max: max)
            return Attempt(results: parsed, error: parsed.isEmpty ? "SearXNG returned no results" : nil)

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

        case .tavily:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "max_results": max, "search_depth": "basic"],
                keyField: "api_key",
                headerName: nil,
                bearer: false,
                max: max
            )

        case .bing:
            return await keyedGet(
                engine: engine,
                url: "\(WebSearchSettings.resolvedURL(for: engine))?q=\(urlEncode(query))&count=\(max)",
                headerName: "Ocp-Apim-Subscription-Key",
                max: max
            ) { parseBingJSON($0, max: max) }

        case .brave:
            return await keyedGet(
                engine: engine,
                url: "\(WebSearchSettings.resolvedURL(for: engine))?q=\(urlEncode(query))&count=\(max)",
                headerName: "X-Subscription-Token",
                max: max
            ) { parseGenericSearchJSON($0, max: max) }

        case .exa:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: [
                    "query": query,
                    "numResults": max,
                    "contents": ["text": ["maxCharacters": 400]],
                ],
                keyField: nil,
                headerName: "x-api-key",
                bearer: false,
                max: max
            )

        case .jina:
            let base = WebSearchSettings.resolvedURL(for: engine).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return await keyedGet(
                engine: engine,
                url: "\(base)/\(urlEncode(query))",
                headerName: "Authorization",
                bearer: true,
                extra: ["Accept": "application/json"],
                max: max
            ) { parseGenericSearchJSON($0, max: max) }

        case .bocha:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "count": max, "summary": true],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .zhipu:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["search_query": query, "count": max],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .kagi:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "workflow": "search", "format": "json", "limit": max],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .linkup:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: [
                    "q": query,
                    "depth": "standard",
                    "outputType": "sourcedAnswer",
                    "includeImages": "false",
                ],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .metaso:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["q": query, "scope": "webpage", "size": max, "includeSummary": false],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .ollama:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "max_results": min(max, 10)],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .parallel:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["objective": query, "search_queries": [query], "mode": "basic"],
                keyField: nil,
                headerName: "x-api-key",
                bearer: false,
                max: max
            )

        case .perplexity:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "max_results": min(max, 20)],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .querit:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "count": max],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .serper:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["q": query, "num": max],
                keyField: nil,
                headerName: "X-API-KEY",
                bearer: false,
                max: max
            )

        case .stepfun:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .tinyfish:
            let base = WebSearchSettings.resolvedURL(for: engine).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return await keyedGet(
                engine: engine,
                url: "\(base)?query=\(urlEncode(query))&num=\(max)",
                headerName: "X-API-Key",
                max: max
            ) { parseGenericSearchJSON($0, max: max) }

        case .you:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "count": max],
                keyField: nil,
                headerName: "X-API-Key",
                bearer: false,
                max: max
            )

        case .firecrawl:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "limit": min(max(max, 1), 100), "sources": ["web", "news"]],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        case .anysearch:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["query": query, "max_results": min(max, 20), "format": "json"],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max
            )

        // Vendor-specific shapes: Doubao's PascalCase envelope, Kimi's chunked
        // text, Grok's answer plus url_citation annotations.
        case .doubao:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: [
                    "Query": query,
                    "SearchType": "web",
                    "Count": max,
                    "Filter": ["NeedUrl": true],
                ],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max,
                parse: parseDoubaoJSON
            )

        case .kimi:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: ["text_query": query, "limit": min(max, 20), "timeout_seconds": 30],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max,
                parse: parseKimiJSON
            )

        case .grok:
            return await keyedPost(
                engine: engine,
                url: WebSearchSettings.resolvedURL(for: engine),
                body: [
                    "model": "grok-4.5",
                    "input": [
                        ["role": "system", "content": grokSystemPrompt],
                        ["role": "user", "content": query],
                    ],
                    "tools": [["type": "web_search"]],
                    "store": false,
                    "stream": false,
                    "reasoning": ["effort": "low"],
                ],
                keyField: nil,
                headerName: nil,
                bearer: true,
                max: max,
                parse: parseGrokJSON
            )
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
        let key = SearchKeyRotator.select(engine)
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
        max: Int,
        parse: (String, Int) throws -> [Result] = { parseGenericSearchJSON($0, max: $1) }
    ) async -> Attempt {
        let key = SearchKeyRotator.select(engine)
        guard !key.isEmpty else { return Attempt(results: [], error: "\(engine.rawValue) API key is not configured") }
        var payload = body
        if let keyField { payload[keyField] = key }
        var headers = ["Accept": "application/json", "Content-Type": "application/json"]
        if let headerName { headers[headerName] = key }
        if bearer { headers["Authorization"] = key.lowercased().hasPrefix("bearer ") ? key : "Bearer \(key)" }
        guard let raw = await postJSON(url, json: payload, extraHeaders: headers) else {
            return Attempt(results: [], error: "empty response from \(engine.rawValue)")
        }
        do {
            let parsed = try parse(raw, max)
            return Attempt(results: parsed, error: parsed.isEmpty ? "\(engine.rawValue) returned no results" : nil)
        } catch {
            // A vendor error envelope is more useful than "no results": it names
            // the bad key or the rejected field.
            return Attempt(results: [], error: error.localizedDescription)
        }
    }

    private static let grokSystemPrompt =
        "You are a search assistant. Answer with the facts you retrieved and cite them."

    // MARK: - Kelivo provider parsers

    /// A vendor error envelope, surfaced instead of an empty result list.
    struct SearchError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Doubao answers `{Result:{WebResults:[{Title,Url,Summary|Content|Snippet}]}}`.
    static func parseDoubaoJSON(_ json: String, max: Int = maxResults) throws -> [Result] {
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        if let metadata = root["ResponseMetadata"] as? [String: Any],
           let error = metadata["Error"] as? [String: Any] {
            // "bad key" is actionable; "no results" is not.
            let message = (error["Message"] as? String) ?? (error["Code"] as? String) ?? "API error"
            throw SearchError(message: message)
        }
        guard let result = root["Result"] as? [String: Any],
              let arr = result["WebResults"] as? [[String: Any]] else { return [] }
        var out: [Result] = []
        for item in arr {
            let url = (item["Url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty else { continue }
            let snippet = firstNonEmpty(item, ["Summary", "Content", "Snippet"])
            out.append(Result(title: (item["Title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines), url: url, snippet: snippet))
            if out.count >= max { break }
        }
        return out
    }

    /// Kimi answers `{search_results:[{title,url,chunks:[{text}]}]}`.
    static func parseKimiJSON(_ json: String, max: Int = maxResults) -> [Result] {
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = root["search_results"] as? [[String: Any]] else { return [] }
        var out: [Result] = []
        for item in arr {
            let url = (item["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty else { continue }
            let chunks = (item["chunks"] as? [[String: Any]] ?? [])
                .map { ($0["text"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            let joined = chunks.joined(separator: "\n\n")
            let snippet = joined.isEmpty ? (item["snippet"] as? String ?? "") : joined
            out.append(Result(title: (item["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines), url: url, snippet: snippet))
            if out.count >= max { break }
        }
        return out
    }

    /// Grok (xAI Responses API) answers with the model's text plus `url_citation`
    /// annotations. The answer is not itself a search result, so only the
    /// citations become results — that is what the caller can follow up on.
    static func parseGrokJSON(_ json: String, max: Int = maxResults) -> [Result] {
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let output = root["output"] as? [[String: Any]] else { return [] }
        var out: [Result] = []
        var seen = Set<String>()
        for item in output {
            guard let content = item["content"] as? [[String: Any]] else { continue }
            for block in content {
                guard let annotations = block["annotations"] as? [[String: Any]] else { continue }
                for annotation in annotations {
                    guard (annotation["type"] as? String) == "url_citation" else { continue }
                    let url = (annotation["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !url.isEmpty, seen.insert(url).inserted else { continue }
                    let title = (annotation["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    out.append(Result(title: title.isEmpty ? url : title, url: url, snippet: ""))
                    if out.count >= max { return out }
                }
            }
        }
        return out
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
    /// query placeholder the query is appended as `q=` — what most self-hosted
    /// search endpoints accept.
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

    /// [T-ios-web-search] Walk an arbitrary provider payload for hit arrays.
    ///
    /// Deliberately a MERGE, not a first-match: You and Firecrawl both publish
    /// `results.web` AND `results.news` (or `data.web` + `data.news`) and their
    /// clients show both, so stopping at the first non-empty array would silently
    /// drop half of every answer. Deduplicated by URL in encounter order.
    ///
    /// No early searx/bing short-circuit here: those parsers only read their own
    /// snippet field, so letting them win would throw away a provider's
    /// `excerpts`/`markdown` text before `parseResultArray` saw it.
    static func parseGenericSearchJSON(_ json: String, max: Int) -> [Result] {
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("["),
           let data = trimmed.data(using: .utf8),
           let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] {
            return parseResultArray(arr, max: max)
        }
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        var merged: [String: Result] = [:]
        var order: [String] = []
        collectResults(node: root, max: max, depth: 0, into: &merged, order: &order)
        return order.compactMap { merged[$0] }
    }

    private static func collectResults(
        node: [String: Any],
        max: Int,
        depth: Int,
        into merged: inout [String: Result],
        order: inout [String]
    ) {
        if merged.count >= max || depth > 4 { return }
        for key in resultKeys {
            guard let arr = node[key] as? [[String: Any]] else { continue }
            for result in parseResultArray(arr, max: max) {
                if merged.count >= max { return }
                if merged[result.url] == nil {
                    merged[result.url] = result
                    order.append(result.url)
                }
            }
        }
        for key in resultKeys {
            guard let child = node[key] as? [String: Any] else { continue }
            collectResults(node: child, max: max, depth: depth + 1, into: &merged, order: &order)
            if merged.count >= max { return }
        }
    }

    /// Hit-array keys seen across the provider set: Kelivo's
    /// `results`/`items`/`data`/`organic`/`webPages`/`value` plus the ones its
    /// extra services use — Kagi's `data.search`, Metaso's `webpages`, LinkUp's
    /// `sources`, Querit's `results.result`, You/Firecrawl's `results.web` + `news`.
    private static let resultKeys = [
        "results", "items", "data", "organic", "organic_results",
        "webPages", "web", "news", "search_result", "value", "sources",
        "webpages", "search", "result", "references", "citations",
    ]

    private static func parseResultArray(_ arr: [[String: Any]], max: Int) -> [Result] {
        var out: [Result] = []
        for item in arr {
            let url = firstNonEmpty(item, ["url", "link", "href", "displayUrl", "Url"])
            let title = firstNonEmpty(item, ["title", "name", "Title"])
            guard !url.isEmpty, !title.isEmpty else { continue }
            var snippet = firstNonEmpty(item, ["snippet", "content", "description", "summary", "text"])
            if snippet.isEmpty, let excerpts = item["excerpts"] as? [String], let first = excerpts.first {
                // Parallel returns `excerpts` (a list).
                snippet = first
            }
            if snippet.isEmpty { snippet = firstNonEmpty(item, ["markdown", "sentence"]) }
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

    /// DuckDuckGo's HTML and Lite endpoints. Regexes are the Android twins',
    /// kept identical so a markup change breaks both platforms at once.
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

    /// Shared by the Bing-HTML engine and the no-key Bing fallback.
    static func parseHTMLLinks(_ html: String, max: Int, pattern: String) -> [Result] {
        guard max > 0 else { return [] }
        var out: [Result] = []
        for match in matches(pattern, in: html) where match.count >= 3 {
            let link = match[1].trimmingCharacters(in: .whitespacesAndNewlines)
            guard link.hasPrefix("http"), !link.contains("bing.com/ck/"), !link.contains("microsoft.com") else { continue }
            let title = stripTags(match[2])
            guard !title.isEmpty else { continue }
            out.append(Result(title: title, url: link, snippet: ""))
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
        return parseHTMLLinks(html, max: max, pattern: pattern)
    }

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
