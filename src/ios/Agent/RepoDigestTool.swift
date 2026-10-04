import Foundation

/// [T-ios-repo-digest] GitHub token for `RepoDigestTool`.
///
/// The token lives in settings rather than in the tool call on purpose: the model
/// should never have to be told a credential (and would then be the one putting it
/// in a transcript). With a token the API limit rises from 60 to 5000 requests per
/// hour and private repositories become readable; public repos work without one.
enum RepoDigestPrefs {
    static let tokenKey = "repo_digest_github_token"

    static var token: String {
        get { (UserDefaults.standard.string(forKey: tokenKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: tokenKey) }
    }

    static var hasToken: Bool { !token.isEmpty }
}

/// [T-ios-repo-digest] Read a GitHub repository the way gitingest does — a file
/// tree plus the contents of the files that matter — in ONE tool call.
///
/// Android twin: `tools/RepoDigestTool.kt`, same filters, same caps, same report.
///
/// Why this exists: given a repo link, the alternatives were `browser_use` (one
/// page at a time, cannot follow a tree) or cloning into the sandbox (a full
/// checkout before the first useful byte). This asks GitHub what the tree IS (one
/// API call), fetches the selected files from `raw.githubusercontent.com` (no API
/// quota), and hands the model a digest with explicit caps and an omitted-files
/// note.
///
/// Host allowlist: github.com / api.github.com / raw.githubusercontent.com. This is
/// not a general proxy — `web_fetch` is that, with its own SSRF guard.
enum RepoDigestTool {
    static let name = "repo_digest"

    private static let apiRoot = "https://api.github.com"
    private static let rawRoot = "https://raw.githubusercontent.com"
    private static let defaultMaxFiles = 40
    private static let hardMaxFiles = 200
    private static let defaultMaxChars = 60_000
    private static let hardMaxChars = 200_000
    private static let maxFileBytes = 300_000

    struct Execution {
        let output: String
        let success: Bool
    }

    struct RepoRef: Equatable {
        let owner: String
        let repo: String
        let ref: String?
        let path: String?
        var slug: String { "\(owner)/\(repo)" }
    }

    struct TreeEntry {
        let path: String
        let type: String
        let size: Int
    }

    static func definition() -> AgentToolDefinition {
        AgentToolDefinition(
            name: name,
            description: "Read a GitHub repository as a digest: the file tree plus the contents of the " +
                "files that matter, in one call — like gitingest. Use this instead of cloning the repo or " +
                "browsing it page by page. Understands repo, /tree/<branch>/<dir> and /blob/<branch>/<file> " +
                "URLs; `format=tree` lists files only (cheapest way to find your way around), `format=files` " +
                "(default) adds contents, `format=file` returns one file. Binary, vendored, minified and " +
                "lock files are skipped automatically; `include`/`exclude` globs narrow that further. Public " +
                "repos need no credentials; a token configured in Settings › Repository digest raises the " +
                "GitHub API rate limit and reaches private repos.",
            parameters: [
                "tool_title": AgentToolParam(type: .string, description: "A concise 5-10 word summary (e.g. 'Digest fastapi repo')."),
                "url": AgentToolParam(type: .string, description: "GitHub repository, directory or file URL (or 'owner/repo')."),
                "ref": AgentToolParam(type: .string, description: "Branch, tag or commit SHA (default: the repo's default branch, or the one in the URL)."),
                "path": AgentToolParam(type: .string, description: "Restrict to this directory or file path inside the repo."),
                "include": AgentToolParam(type: .string, description: "Comma-separated globs that files must match (e.g. '*.kt,src/**')."),
                "exclude": AgentToolParam(type: .string, description: "Comma-separated globs to skip (e.g. '**/test/**,docs/**')."),
                "format": AgentToolParam(
                    type: .string,
                    description: "tree = file list only; files (default) = tree + contents; file = a single file's contents.",
                    enumValues: ["tree", "files", "file"]
                ),
                "max_files": AgentToolParam(type: .integer, description: "How many files to include (default 40, maximum 200)."),
                "max_chars": AgentToolParam(type: .integer, description: "Total character budget for the digest (default 60000, maximum 200000)."),
            ],
            required: ["tool_title", "url"],
            propertyOrdering: ["tool_title", "url", "ref", "path", "include", "exclude", "format"]
        )
    }

    static func execute(argsJSON: String) async -> Execution {
        guard let data = argsJSON.data(using: .utf8),
              let args = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return Execution(output: "repo_digest: invalid JSON", success: false)
        }
        let toolTitle = (args["tool_title"] as? String) ?? name
        _ = toolTitle
        guard let repo = parseRepoURL(args["url"] as? String ?? "") else {
            return Execution(
                output: "repo_digest: expected a github.com repository, /tree/… or /blob/… URL (or owner/repo)",
                success: false
            )
        }
        let explicitRef = (args["ref"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let pathArg = (args["path"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let format = ((args["format"] as? String) ?? "files").lowercased()
        let maxFiles = min(max((args["max_files"] as? Int) ?? defaultMaxFiles, 1), hardMaxFiles)
        let maxChars = min(max((args["max_chars"] as? Int) ?? defaultMaxChars, 500), hardMaxChars)
        let include = parseGlobs(args["include"] as? String ?? "")
        let exclude = parseGlobs(args["exclude"] as? String ?? "")
        let token = RepoDigestPrefs.token

        let ref = explicitRef.isEmpty
            ? (repo.ref ?? "")
            : explicitRef
        let resolvedRef = ref.isEmpty ? (await defaultBranch(repo, token: token) ?? "HEAD") : ref
        let wantedPath = pathArg.isEmpty ? (repo.path ?? "") : pathArg
        let wantSingleFile = format == "file" || (args["url"] as? String ?? "").contains("/blob/")

        if wantSingleFile, !wantedPath.isEmpty {
            guard let content = await fetchRaw(repo, ref: resolvedRef, path: wantedPath, token: token),
                  let text = String(data: content, encoding: .utf8) else {
                return Execution(
                    output: "repo_digest: could not read \(wantedPath) at \(resolvedRef) (wrong path or private repo without a token?)",
                    success: false
                )
            }
            let output = """
            Repo: \(repo.slug) @ \(resolvedRef)
            File: \(wantedPath) (\(content.count) bytes)

            \(sanitize(text, maxChars: maxChars))
            """
            return Execution(output: output, success: true)
        }

        guard let entries = await listTree(repo, ref: resolvedRef, token: token) else {
            return Execution(
                output: "repo_digest: GitHub did not return a tree for \(repo.slug) @ \(resolvedRef) " +
                    "(rate limit? configure a token in Settings › Repository digest)",
                success: false
            )
        }

        let matching = entries.filter { entry in
            entry.type == "blob" &&
                (wantedPath.isEmpty || entry.path == wantedPath || entry.path.hasPrefix("\(wantedPath)/")) &&
                matchesGlobs(entry.path, include: include, exclude: exclude) &&
                !shouldSkip(entry.path)
        }.sorted { $0.path < $1.path }

        let fetchable = matching.filter { $0.size <= maxFileBytes }
        let tooBig = matching.count - fetchable.count
        let selected = format == "tree" ? [] : Array(fetchable.prefix(maxFiles))
        let notFetched = fetchable.count - selected.count

        var contents: [String: Data] = [:]
        if !selected.isEmpty {
            // Bounded concurrency: a big repo would otherwise open hundreds of
            // sockets at once, and the digest waits on the slowest either way.
            await withTaskGroup(of: (String, Data?).self) { group in
                var iterator = selected.makeIterator()
                var inFlight = 0
                let limit = 6
                while inFlight < limit, let entry = iterator.next() {
                    inFlight += 1
                    group.addTask {
                        (entry.path, await fetchRaw(repo, ref: resolvedRef, path: entry.path, token: token))
                    }
                }
                while let result = await group.next() {
                    if let data = result.1 { contents[result.0] = data }
                    if let entry = iterator.next() {
                        group.addTask {
                            (entry.path, await fetchRaw(repo, ref: resolvedRef, path: entry.path, token: token))
                        }
                    }
                }
            }
        }

        var lines: [String] = []
        lines.append("Repo: \(repo.slug) @ \(resolvedRef)" + (wantedPath.isEmpty ? "" : " (path: \(wantedPath))"))
        lines.append("Files: \(matching.count) matched" + (format == "tree" ? "" : ", \(selected.count) included"))
        if let note = omissionNote(notFetched: notFetched, tooBig: tooBig, maxFiles: maxFiles) { lines.append(note) }
        lines.append("")
        lines.append("Tree (\(matching.count)):")
        lines.append(treeText(matching, budget: maxChars / 3))
        if format != "tree" {
            lines.append("")
            var total = lines.joined(separator: "\n").count
            for entry in selected {
                guard let data = contents[entry.path],
                      let text = String(data: data, encoding: .utf8) else { continue }
                let remaining = maxChars - total
                if remaining <= 200 {
                    lines.append("[budget exhausted — remaining files omitted; raise max_chars or narrow the path]")
                    break
                }
                let header = "=== \(entry.path) (\(data.count) bytes) ==="
                let body = sanitize(text, maxChars: min(remaining - header.count - 2, maxFileBytes))
                lines.append(header)
                lines.append(body)
                lines.append("")
                total += header.count + body.count + 2
            }
        }
        return Execution(output: lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines), success: true)
    }

    // MARK: - pure helpers

    /// Parse any of the shapes a user actually pastes: bare `owner/repo`, a repo
    /// URL, a `/tree/<ref>/<dir>` link, a `/blob/<ref>/<file>` link.
    static func parseRepoURL(_ rawURL: String, knownRefs: Set<String>? = nil) -> RepoRef? {
        var s = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        for prefix in ["https://", "http://"] where s.hasPrefix(prefix) {
            s = String(s.dropFirst(prefix.count))
        }
        if s.hasPrefix("www.") { s = String(s.dropFirst(4)) }
        if s.hasPrefix("github.com/") { s = String(s.dropFirst("github.com/".count)) }
        s = s.split(separator: "?").first.map(String.init) ?? s
        s = s.split(separator: "#").first.map(String.init) ?? s
        while s.hasSuffix("/") { s.removeLast() }
        guard !s.isEmpty else { return nil }
        let parts = s.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        guard parts.count >= 2 else { return nil }
        let owner = parts[0]
        var repo = parts[1]
        if repo.hasSuffix(".git") { repo = String(repo.dropLast(4)) }
        guard !owner.isEmpty, !repo.isEmpty else { return nil }
        var ref: String?
        var path: String?
        if parts.count >= 3, parts[2] == "tree" || parts[2] == "blob" {
            let rest = Array(parts.dropFirst(3))
            guard !rest.isEmpty else { return RepoRef(owner: owner, repo: repo, ref: nil, path: nil) }
            if let knownRefs {
                let joined = rest.joined(separator: "/")
                // Longest matching ref wins, so `feature/x` beats `feature`.
                if let match = knownRefs.filter({ joined.hasPrefix($0) }).max(by: { $0.count < $1.count }) {
                    ref = match
                    let tail = String(joined.dropFirst(match.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    path = tail.isEmpty ? nil : tail
                    return RepoRef(owner: owner, repo: repo, ref: ref, path: path)
                }
            }
            ref = rest[0]
            let tail = rest.dropFirst().joined(separator: "/")
            path = tail.isEmpty ? nil : tail
        } else if parts.count > 2 {
            path = parts.dropFirst(2).joined(separator: "/")
        }
        return RepoRef(owner: owner, repo: repo, ref: ref, path: path)
    }

    static func parseGlobs(_ raw: String) -> [String] {
        raw.split(whereSeparator: { $0 == "," || $0 == "\n" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    static func matchesGlobs(_ path: String, include: [String], exclude: [String]) -> Bool {
        if exclude.contains(where: { globMatches($0, path: path) }) { return false }
        if include.isEmpty { return true }
        return include.contains(where: { globMatches($0, path: path) })
    }

    /// `*` within a segment, `**` across segments, `?` one character.
    static func globMatches(_ glob: String, path: String) -> Bool {
        var regex = "^"
        var index = glob.startIndex
        while index < glob.endIndex {
            let ch = glob[index]
            if ch == "*" {
                let next = glob.index(after: index)
                if next < glob.endIndex, glob[next] == "*" {
                    let afterNext = glob.index(after: next)
                    if afterNext < glob.endIndex, glob[afterNext] == "/" {
                        // `**/` may match zero segments, so a/**/b also matches a/b.
                        regex += "(?:.*/)?"
                        index = glob.index(after: afterNext)
                        continue
                    }
                    regex += ".*"
                    index = afterNext
                    continue
                }
                regex += "[^/]*"
                index = next
                continue
            }
            if ch == "?" {
                regex += "[^/]"
                index = glob.index(after: index)
                continue
            }
            if ".+()|[]{}^$\\".contains(ch) { regex += "\\" }
            regex.append(ch)
            index = glob.index(after: index)
        }
        regex += "$"
        return path.range(of: regex, options: .regularExpression) != nil
    }

    /// Files not worth spending the budget on: binaries, vendored trees, build
    /// output, minified bundles and lock files.
    static func shouldSkip(_ path: String) -> Bool {
        let lower = path.lowercased()
        let segments = lower.split(separator: "/").map(String.init)
        if segments.contains(where: { skippedDirs.contains($0) }) { return true }
        let name = segments.last ?? lower
        if skippedFiles.contains(name) { return true }
        if lower.hasSuffix(".min.js") || lower.hasSuffix(".min.css") { return true }
        if name.hasPrefix("package-lock") || name.hasPrefix("yarn.lock") || name == "pnpm-lock.yaml" { return true }
        let ext = name.contains(".") ? String(name.split(separator: ".").last ?? "") : ""
        return binaryExtensions.contains(ext)
    }

    private static let skippedDirs: Set<String> = [
        ".git", "node_modules", "vendor", "dist", "build", "out", "target",
        "__pycache__", ".venv", "venv", "site-packages", ".idea", ".gradle",
        "pods", "deriveddata", ".next", ".nuxt", "coverage", ".mypy_cache",
    ]

    private static let skippedFiles: Set<String> = [
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock",
        "cargo.lock", "gemfile.lock", "composer.lock", "go.sum",
    ]

    private static let binaryExtensions: Set<String> = [
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tif", "tiff", "svgz",
        "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "apk",
        "aar", "so", "dylib", "dll", "exe", "bin", "o", "a", "class", "pyc",
        "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "mov", "avi", "wav",
        "flac", "ogg", "webm", "heic", "psd", "sketch", "xlsx", "xls", "docx",
        "doc", "pptx", "ppt", "db", "sqlite", "sqlite3", "onnx", "tflite", "pb",
        "wasm", "keystore", "jks", "p12", "der", "pem", "lock",
    ]

    static func treeText(_ entries: [TreeEntry], budget: Int) -> String {
        var out = ""
        for entry in entries {
            let line = "\(entry.path) (\(entry.size))\n"
            if out.count + line.count > budget {
                out += "[tree truncated: \(entries.count) entries total]\n"
                break
            }
            out += line
        }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func omissionNote(notFetched: Int, tooBig: Int, maxFiles: Int) -> String? {
        var notes: [String] = []
        if notFetched > 0 { notes.append("\(notFetched) beyond the \(maxFiles)-file cap") }
        if tooBig > 0 { notes.append("\(tooBig) larger than \(maxFileBytes / 1000)KB") }
        guard !notes.isEmpty else { return nil }
        return "Omitted: \(notes.joined(separator: ", ")) — raise max_files or narrow path/include."
    }

    static func sanitize(_ text: String, maxChars: Int) -> String {
        let withoutNul = text.contains("\u{0}") ? text.replacingOccurrences(of: "\u{0}", with: "") : text
        guard withoutNul.count > maxChars else { return withoutNul }
        return String(withoutNul.prefix(maxChars)) + "\n… [file truncated at \(maxChars) chars]"
    }

    static func encodePath(_ raw: String) -> String {
        raw.split(separator: "/").map { segment in
            segment.addingPercentEncoding(withAllowedCharacters: pathSegmentAllowed) ?? String(segment)
        }.joined(separator: "/")
    }

    private static let pathSegmentAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~!$&'()*+,;=:@")
        return set
    }()

    // MARK: - network

    private static func request(_ url: String, token: String, accept: String) -> URLRequest? {
        guard let parsed = URL(string: url) else { return nil }
        var request = URLRequest(url: parsed)
        request.timeoutInterval = 45
        request.setValue("OpenMinis/repo_digest", forHTTPHeaderField: "User-Agent")
        request.setValue(accept, forHTTPHeaderField: "Accept")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return request
    }

    private static func defaultBranch(_ repo: RepoRef, token: String) async -> String? {
        guard let body = await get("\(apiRoot)/repos/\(repo.slug)", token: token, accept: "application/vnd.github+json"),
              let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let branch = (root["default_branch"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return branch.isEmpty ? nil : branch
    }

    private static func listTree(_ repo: RepoRef, ref: String, token: String) async -> [TreeEntry]? {
        let url = "\(apiRoot)/repos/\(repo.slug)/git/trees/\(encodePath(ref))?recursive=1"
        guard let body = await get(url, token: token, accept: "application/vnd.github+json"),
              let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let array = root["tree"] as? [[String: Any]] else { return nil }
        return array.compactMap { item in
            let path = item["path"] as? String ?? ""
            guard !path.isEmpty else { return nil }
            return TreeEntry(
                path: path,
                type: item["type"] as? String ?? "",
                size: item["size"] as? Int ?? 0
            )
        }
    }

    private static func fetchRaw(_ repo: RepoRef, ref: String, path: String, token: String) async -> Data? {
        let url = "\(rawRoot)/\(repo.owner)/\(repo.repo)/\(encodePath(ref))/\(encodePath(path))"
        // The raw host is allowlisted, but the guard still runs: a redirect could
        // point anywhere, and the check is free.
        if let blocked = FetchUrlGuard.blockedReason(url) {
            _ = blocked
            return nil
        }
        guard let request = request(url, token: token, accept: "text/plain") else { return nil }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return data.count > maxFileBytes ? data.prefix(maxFileBytes) : data
        } catch {
            return nil
        }
    }

    private static func get(_ url: String, token: String, accept: String) async -> String? {
        if FetchUrlGuard.blockedReason(url) != nil { return nil }
        guard let request = request(url, token: token, accept: accept) else { return nil }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }
}
