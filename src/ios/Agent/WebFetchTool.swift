import Foundation

/// [T-ios-web-fetch] Fetch a URL and return its content, formatted for a model to
/// read — the tool that replaces "curl through the terminal" and "open it in the
/// browser and copy the text".
///
/// Android twin: `tools/WebFetchTool.kt` — same parameters, same formats, same
/// guards; kept in step deliberately so the two clients present one contract.
///
/// Beyond the plain reader both tools started as (`web_search`'s sibling), this
/// covers what the two manual routes were actually used for: custom **headers**
/// (auth tokens, `Accept`, `Referer`), **method/body** for documented JSON
/// endpoints, **JavaScript** via `render: true` (the real browser engine, for
/// client-rendered pages) and an explicit **format** (text / markdown / html /
/// pretty json).
///
/// Everything is bounded: SSRF via `FetchUrlGuard`, response size, and an output
/// cap that SAYS when it cut, because a model that cannot see the cut will quote
/// it as if it were the whole page.
enum WebFetchTool {
    static let name = "web_fetch"
    private static let defaultMaxChars = 8_000
    private static let hardMaxChars = 50_000
    /// Absolute ceiling before extraction: what we are willing to hold in memory.
    private static let maxBodyBytes = 2_000_000

    struct Execution {
        let output: String
        let success: Bool
    }

    static func definition() -> AgentToolDefinition {
        AgentToolDefinition(
            name: name,
            description: "Fetch a public http(s) URL and return its content as readable text " +
                "(or markdown, raw HTML, or pretty JSON). Use web_search first to find URLs, then this " +
                "instead of shelling out to curl or opening the page in the browser. Supports custom " +
                "request headers, POST bodies, and `render: true` to run the page's JavaScript in the " +
                "real browser engine — use that only when the plain fetch returns an app shell or empty " +
                "content, since it is much slower. Private/loopback/metadata hosts are blocked.",
            parameters: [
                "tool_title": AgentToolParam(
                    type: .string,
                    description: "A concise 5-10 word summary shown to the user (e.g. 'Fetch FastAPI changelog'). Use the same language as the user."
                ),
                "url": AgentToolParam(type: .string, description: "Absolute http or https URL."),
                "method": AgentToolParam(
                    type: .string,
                    description: "HTTP method (default GET). Use POST with `body` for documented JSON endpoints, or HEAD to check existence/headers cheaply.",
                    enumValues: ["GET", "POST", "HEAD"]
                ),
                "headers": AgentToolParam(
                    type: .string,
                    description: "Extra request headers, as a JSON object ({\"X-Api-Key\":\"…\"}) or as `Name: value` lines. Use this for auth tokens, Accept, Accept-Language, Referer."
                ),
                "body": AgentToolParam(type: .string, description: "Request body for POST."),
                "content_type": AgentToolParam(type: .string, description: "Content-Type for `body` (default application/json)."),
                "format": AgentToolParam(
                    type: .string,
                    description: "How to return the content: text (default, readable), markdown (keeps headings/links/lists), html (raw), json (pretty-printed when it parses).",
                    enumValues: ["text", "markdown", "html", "json"]
                ),
                "max_chars": AgentToolParam(
                    type: .integer,
                    description: "Maximum characters to return (default 8000, maximum 50000). The output says when it was cut."
                ),
                "render": AgentToolParam(
                    type: .boolean,
                    description: "Run the page in the browser engine so its JavaScript executes before extraction. Slower (a real page load plus `wait_ms`), and it loads the page from your device, so use it for client-rendered sites only."
                ),
                "wait_ms": AgentToolParam(type: .integer, description: "render only: how long to let the page settle after load (default 1500)."),
                "timeout_seconds": AgentToolParam(type: .integer, description: "Request timeout in seconds (default 45, maximum 120)."),
            ],
            required: ["tool_title", "url"],
            propertyOrdering: ["tool_title", "url", "method", "headers", "body", "format", "render"]
        )
    }

    /// - Parameter render: supplied by the caller when the browser engine is
    ///   available: `(url, waitMs, wantHtml) -> rendered content`, or nil.
    static func execute(
        argsJSON: String,
        render: ((String, Int, Bool) async -> String?)? = nil
    ) async -> Execution {
        guard let data = argsJSON.data(using: .utf8),
              let args = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return Execution(output: "Error: arguments were not a JSON object", success: false)
        }
        let toolTitle = (args["tool_title"] as? String) ?? name
        let url = (args["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        if let blocked = FetchUrlGuard.blockedReason(url) {
            return Execution(output: "Error: \(blocked)", success: false)
        }

        let method = ((args["method"] as? String) ?? "GET").uppercased()
        let format = ((args["format"] as? String) ?? "text").lowercased()
        let requestedMax = (args["max_chars"] as? Int) ?? Int(args["max_chars"] as? String ?? "") ?? defaultMaxChars
        let maxChars = min(max(requestedMax, 200), hardMaxChars)
        let wantHtml = format == "html"
        let headers = parseHeaders(args["headers"] as? String ?? "")
        let body = args["body"] as? String ?? ""
        let contentType = args["content_type"] as? String ?? "application/json"
        let timeoutSeconds = min(max((args["timeout_seconds"] as? Int) ?? 45, 5), 120)

        // JavaScript first when asked for, but never as a hard dependency: if the
        // engine is missing or the load fails we fall through to the plain
        // request, because a degraded answer beats no answer.
        if (args["render"] as? Bool) == true, let render {
            let waitMs = min(max((args["wait_ms"] as? Int) ?? 1500, 0), 15_000)
            if let rendered = await render(url, waitMs, wantHtml), !rendered.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let content: String
                if format == "markdown" {
                    content = htmlToMarkdown(rendered, baseURL: url)
                } else if wantHtml {
                    content = rendered
                } else {
                    content = htmlToText(rendered)
                }
                let output = """
                URL: \(url)
                Rendered with JavaScript (\(waitMs)ms settle).

                \(truncateWithNote(content.trimmingCharacters(in: .whitespacesAndNewlines), maxChars: maxChars))
                """
                return Execution(output: output, success: true)
            }
        }

        guard let requestURL = URL(string: url) else {
            return Execution(output: "Error: invalid URL", success: false)
        }
        if let host = requestURL.host, let blocked = FetchUrlGuard.blockedReasonForResolution(host) {
            return Execution(output: "Error: \(blocked)", success: false)
        }

        var request = URLRequest(url: requestURL)
        request.httpMethod = method
        request.timeoutInterval = TimeInterval(timeoutSeconds)
        let userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        if !headers.keys.contains(where: { $0.lowercased() == "accept" }) {
            request.setValue("text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        }
        if !headers.keys.contains(where: { $0.lowercased() == "accept-language" }) {
            request.setValue("en,zh-CN;q=0.8", forHTTPHeaderField: "Accept-Language")
        }
        if method == "POST" {
            request.httpBody = body.data(using: .utf8)
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }

        do {
            let (raw, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return Execution(output: "Error: no HTTP response for \(url)", success: false)
            }
            let finalURL = http.url?.absoluteString ?? url
            // A redirect is a NEW URL, and the model never saw it: re-check it.
            if let blocked = FetchUrlGuard.blockedReason(finalURL) {
                return Execution(output: "Error after redirect: \(blocked)", success: false)
            }
            if let port = http.url?.port, port != 80, port != 443 {
                return Execution(output: "Error: refusing to read a non-standard port (\(port)) — \(finalURL)", success: false)
            }

            let capped = raw.count > maxBodyBytes ? raw.prefix(maxBodyBytes) : raw
            let text = String(data: capped, encoding: .utf8)
                ?? String(data: capped, encoding: .isoLatin1)
                ?? ""
            let contentTypeHeader = http.value(forHTTPHeaderField: "Content-Type") ?? ""

            if !(200..<300).contains(http.statusCode) {
                // Include the body: an API error explains itself there.
                let detail = truncateWithNote(text.trimmingCharacters(in: .whitespacesAndNewlines), maxChars: 1200)
                return Execution(
                    output: "HTTP \(http.statusCode) for \(finalURL)\n\(contentTypeHeader)\n\n\(detail)",
                    success: false
                )
            }

            let looksJSON = contentTypeHeader.lowercased().contains("json") ||
                text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") ||
                text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("[")
            let looksHTML = contentTypeHeader.lowercased().contains("html")

            let content: String
            switch format {
            case "html":
                content = text
            case "json":
                content = prettyJSONIfPossible(text) ?? text
            case "markdown":
                content = looksHTML ? htmlToMarkdown(text, baseURL: finalURL) : text
            default:
                if looksHTML {
                    content = htmlToText(text)
                } else if looksJSON {
                    content = prettyJSONIfPossible(text) ?? text
                } else {
                    content = text
                }
            }

            var header = """
            URL: \(finalURL)
            Status: \(http.statusCode)
            """
            if !contentTypeHeader.isEmpty { header += "\nContent-Type: \(contentTypeHeader)" }
            header += "\nBytes: \(raw.count)\(raw.count >= maxBodyBytes ? " (capped)" : "")"

            return Execution(
                output: "\(header)\n\n\(truncateWithNote(content.trimmingCharacters(in: .whitespacesAndNewlines), maxChars: maxChars))",
                success: true
            )
        } catch {
            return Execution(output: "Error fetching \(url): \(error.localizedDescription)", success: false)
        }
    }

    // MARK: - pure helpers (mirrored by the Android unit tests)

    /// Headers, from a JSON object or from `Name: value` lines.
    ///
    /// Both spellings exist because the model writes whichever comes naturally,
    /// and rejecting one produces a tool call that looks right and silently drops
    /// the credential. Hop-by-hop headers the client owns are skipped, as are
    /// names containing a space or colon (header splitting).
    static func parseHeaders(_ raw: String) -> [String: String] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return [:] }
        var out: [String: String] = [:]
        func put(_ name: String, _ value: String) {
            let key = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty else { return }
            guard !key.contains(where: { $0 == " " || $0 == ":" || $0 == "\n" || $0 == "\r" }) else { return }
            guard !ignoredHeaders.contains(key.lowercased()) else { return }
            out[key] = value.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if trimmed.hasPrefix("{") {
            guard let data = trimmed.data(using: .utf8),
                  let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [:] }
            for (key, value) in obj { put(key, value as? String ?? String(describing: value)) }
            return out
        }
        for line in trimmed.split(whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            guard let idx = line.firstIndex(of: ":") else { continue }
            let name = String(line[line.startIndex..<idx])
            let value = String(line[line.index(after: idx)...])
            put(name, value)
        }
        return out
    }

    /// Headers the HTTP client owns; letting the model set them breaks the request.
    private static let ignoredHeaders: Set<String> = [
        "host", "content-length", "connection", "transfer-encoding",
        "expect", "upgrade", "te", "trailer", "proxy-connection",
    ]

    /// Pretty-print when the body is JSON; nil when it is not.
    static func prettyJSONIfPossible(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first, first == "{" || first == "[" else { return nil }
        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]) else {
            return nil
        }
        return String(data: pretty, encoding: .utf8)
    }

    /// Readable text: scripts/styles dropped, block tags turned into line breaks so
    /// paragraphs and list items do not run together, entities decoded.
    static func htmlToText(_ raw: String) -> String {
        var s = raw
        s = s.replacingOccurrences(of: "(?is)<script[^>]*>.*?</script>", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<style[^>]*>.*?</style>", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<noscript[^>]*>.*?</noscript>", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<head[^>]*>.*?</head>", with: " ", options: .regularExpression)
        // Block boundaries become newlines BEFORE tags are stripped.
        s = s.replacingOccurrences(of: "(?i)<br\\s*/?>", with: "\n", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?i)</(p|div|section|article|li|tr|h[1-6]|blockquote|pre)>", with: "\n", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?i)<li[^>]*>", with: "\n- ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<[^>]+>", with: " ", options: .regularExpression)
        s = decodeEntities(s)
        let lines = s.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            line.replacingOccurrences(of: "[ \\t\\u{00a0}]+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return lines.filter { !$0.isEmpty }
            .joined(separator: "\n")
            .replacingOccurrences(of: "\\n{3,}", with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Markdown: the same text with structure a model can still see — headings,
    /// list items, links (absolute, resolved against `baseURL`), fenced code and
    /// inline emphasis. Tables and images degrade to text on purpose: the value
    /// here is that links survive, without shipping a converter dependency.
    static func htmlToMarkdown(_ raw: String, baseURL: String? = nil) -> String {
        var s = raw
        s = s.replacingOccurrences(of: "(?is)<script[^>]*>.*?</script>", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<style[^>]*>.*?</style>", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<noscript[^>]*>.*?</noscript>", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<head[^>]*>.*?</head>", with: " ", options: .regularExpression)
        s = replace(s, "(?is)<pre[^>]*>(.*?)</pre>") { groups in
            "\n```\n" + decodeEntities(groups[1].replacingOccurrences(of: "(?is)<[^>]+>", with: "", options: .regularExpression)) + "\n```\n"
        }
        for level in 1...6 {
            s = replace(s, "(?is)<h\(level)[^>]*>(.*?)</h\(level)>") { groups in
                "\n" + String(repeating: "#", count: level) + " " + collapseSpaces(groups[1]) + "\n"
            }
        }
        s = replace(s, "(?is)<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>") { groups in
            let text = collapseSpaces(groups[2])
            let href = absoluteURL(groups[1], baseURL: baseURL)
            if text.isEmpty { return href }
            if href.isEmpty { return text }
            return "[\(text)](\(href))"
        }
        s = s.replacingOccurrences(of: "(?is)</?(strong|b)(\\s[^>]*)?>", with: "**", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)</?(em|i)(\\s[^>]*)?>", with: "*", options: .regularExpression)
        s = replace(s, "(?is)<code[^>]*>(.*?)</code>") { groups in "`" + collapseSpaces(groups[1]) + "`" }
        s = s.replacingOccurrences(of: "(?i)<li[^>]*>", with: "\n- ", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?i)<br\\s*/?>", with: "\n", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?i)</(p|div|section|article|li|tr|h[1-6]|blockquote)>", with: "\n", options: .regularExpression)
        s = s.replacingOccurrences(of: "(?is)<[^>]+>", with: " ", options: .regularExpression)
        s = decodeEntities(s)
        let lines = s.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            line.replacingOccurrences(of: "[ \\t\\u{00a0}]+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
        }
        return lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .joined(separator: "\n")
            .replacingOccurrences(of: "\\n{3,}", with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Relative links keep their meaning once resolved against the page URL.
    static func absoluteURL(_ href: String, baseURL: String?) -> String {
        let raw = href.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return raw }
        if raw.hasPrefix("#") || raw.hasPrefix("data:") || raw.hasPrefix("javascript:") { return raw }
        guard let baseURL, !baseURL.isEmpty, let base = URL(string: baseURL) else { return raw }
        return URL(string: raw, relativeTo: base)?.absoluteString ?? raw
    }

    /// Says explicitly that content was cut — a silent cut gets quoted as the whole page.
    static func truncateWithNote(_ text: String, maxChars: Int) -> String {
        guard text.count > maxChars else { return text }
        return String(text.prefix(maxChars)) +
            "\n\n[truncated: showing \(maxChars) of \(text.count) characters — request a narrower page or a larger max_chars]"
    }

    private static func collapseSpaces(_ raw: String) -> String {
        decodeEntities(raw.replacingOccurrences(of: "(?is)<[^>]+>", with: " ", options: .regularExpression))
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    private static func decodeEntities(_ raw: String) -> String {
        raw.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&mdash;", with: "—")
            .replacingOccurrences(of: "&ndash;", with: "–")
            .replacingOccurrences(of: "&hellip;", with: "…")
    }

    /// `NSRegularExpression` replacement with a closure: group 0 is the whole
    /// match, so the captured groups are 1...n.
    private static func replace(_ text: String, _ pattern: String, _ transform: ([String]) -> String) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return text }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        var result = ""
        var cursor = text.startIndex
        for match in regex.matches(in: text, options: [], range: range) {
            guard let matchRange = Range(match.range, in: text) else { continue }
            result += text[cursor..<matchRange.lowerBound]
            let groups = (0..<match.numberOfRanges).map { index -> String in
                guard let r = Range(match.range(at: index), in: text) else { return "" }
                return String(text[r])
            }
            result += transform(groups)
            cursor = matchRange.upperBound
        }
        result += text[cursor...]
        return result
    }
}
