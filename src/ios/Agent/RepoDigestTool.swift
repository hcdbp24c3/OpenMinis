import Foundation

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

    /// Git hosts the tool reads through their own API.
    enum Provider: String {
        case github
        case gitlab
        case gitea
        case bitbucket
        case unknown

        var label: String {
            switch self {
            case .github: return "GitHub"
            case .gitlab: return "GitLab"
            case .gitea: return "Gitea/Forgejo/Codeberg"
            case .bitbucket: return "Bitbucket Cloud"
            case .unknown: return "unknown host"
            }
        }
    }

    /// owner/repo/ref/path lifted out of any of the URL shapes a forge uses, plus
    /// the origin and provider they were read from.
    ///
    /// `origin` is kept so every later request goes back to the host the user
    /// actually named — which is also why the SSRF guard runs per request rather
    /// than once on the input URL.
    struct RepoRef: Equatable {
        let owner: String
        let repo: String
        let ref: String?
        let path: String?
        var origin: String = "https://github.com"
        var provider: Provider = .github
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
            description: "Read a git repository as a digest: the file tree plus the contents of the files " +
                "that matter, in one call — like gitingest. Use this instead of cloning the repo or browsing it " +
                "page by page. Works with GitHub, GitLab (including self-hosted), Gitea/Forgejo/Codeberg/Gogs, " +
                "Bitbucket Cloud, and probes other hosts with both API shapes before giving up. Understands repo, " +
                "`/tree/<ref>/<dir>`, `/-/tree/<ref>/<dir>`, `/src/branch/<ref>/<dir>` and the matching blob URLs; " +
                "`format=tree` lists files only (cheapest way to find your way around), `format=files` (default) " +
                "adds contents, `format=file` returns one file. Binary, vendored, minified and lock files are " +
                "skipped automatically; `include`/`exclude` globs narrow that further. Public repos need no " +
                "credentials; tokens configured in Settings › Repository digest raise the API limits and reach " +
                "private repos.",
            parameters: [
                "tool_title": AgentToolParam(type: .string, description: "A concise 5-10 word summary (e.g. 'Digest fastapi repo')."),
                "url": AgentToolParam(type: .string, description: "Repository, directory or file URL — any supported host — or 'owner/repo' for GitHub."),
                "ref": AgentToolParam(type: .string, description: "Branch, tag or commit SHA (default: the repo's default branch, or the one in the URL). Required for a branch whose name contains a slash on hosts that put the ref in the path (Bitbucket)."),
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
        // Credentials come from the git vault, matched on the HOST the URL names — not
        // on the forge family — so a company GitLab and gitlab.com carry different
        // tokens and a fleet of self-hosted forges needs no code change. The header
        // shape stays provider-driven (see `adapterFor`); the vault only supplies the
        // secret. Resolved once: identifying the provider does not change the host, so
        // a probe never needs a second lookup.
        let host = GitVault.normalizeHost(repo.origin)
        let token = GitVault.token(host: host)

        // Unknown hosts are probed once, here: Gitea's API first (most self-hosted
        // forges are Gitea/Forgejo/Gogs), then GitLab's. Probing per FILE would
        // multiply the cost of every fetch, so it is resolved up front.
        let provider = await resolveProvider(repo, token: token)
        let target = RepoRef(owner: repo.owner, repo: repo.repo, ref: repo.ref, path: repo.path, origin: repo.origin, provider: provider)
        guard provider != .unknown else {
            return Execution(
                output: "repo_digest: \(target.origin) did not answer either the Gitea or the GitLab API. " +
                    "If it is a plain git server, clone it with the shell tool (`git clone --depth 1 <url>`), " +
                    "or fetch a single file with web_fetch.",
                success: false
            )
        }
        let adapter = adapterFor(provider)

        let ref = explicitRef.isEmpty ? (repo.ref ?? "") : explicitRef
        let resolvedRef = ref.isEmpty ? (await adapter.defaultBranch(target, token: token) ?? "HEAD") : ref
        let wantedPath = pathArg.isEmpty ? (repo.path ?? "") : pathArg
        let wantSingleFile = format == "file" || (args["url"] as? String ?? "").contains("/blob/")

        if wantSingleFile, !wantedPath.isEmpty {
            guard let content = await adapter.fetchRaw(target, ref: resolvedRef, path: wantedPath, token: token),
                  let text = String(data: content, encoding: .utf8) else {
                return Execution(
                    output: "repo_digest: could not read \(wantedPath) at \(resolvedRef) (wrong path or private repo without a token?)",
                    success: false
                )
            }
            let output = """
            Repo: \(target.origin)/\(target.slug) @ \(resolvedRef)
            File: \(wantedPath) (\(content.count) bytes)

            \(sanitize(text, maxChars: maxChars))
            """
            return Execution(output: output, success: true)
        }

        guard let entries = await adapter.listTree(target, ref: resolvedRef, token: token) else {
            return Execution(
                output: "repo_digest: \(provider.label) did not return a tree for \(target.slug) @ \(resolvedRef) " +
                    "(rate limit, wrong ref, or private repo without a token?)",
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
                        (entry.path, await adapter.fetchRaw(target, ref: resolvedRef, path: entry.path, token: token))
                    }
                }
                while let result = await group.next() {
                    if let data = result.1 { contents[result.0] = data }
                    if let entry = iterator.next() {
                        group.addTask {
                            (entry.path, await adapter.fetchRaw(target, ref: resolvedRef, path: entry.path, token: token))
                        }
                    }
                }
            }
        }

        var lines: [String] = []
        lines.append("Repo: \(target.origin)/\(target.slug) @ \(resolvedRef) (\(provider.label))" + (wantedPath.isEmpty ? "" : " (path: \(wantedPath))"))
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

    /// Provider detection from the host, then from the URL's own shape. The shape
    /// matters for self-hosted instances: `/-/` is GitLab's signature (stock GitLab,
    /// or GitLab CE on a company domain), `/src/branch/` is Gitea's, and both are far
    /// more reliable than trying to recognise hostnames.
    static func detectProvider(host: String, url: String) -> Provider {
        let h = host.lowercased()
        if h == "github.com" || h.hasSuffix(".github.com") { return .github }
        if h == "gitlab.com" || h.hasSuffix(".gitlab.com") || url.contains("/-/") { return .gitlab }
        if h == "codeberg.org" || h.contains("gitea") || h.contains("forgejo") || h.contains("gogs") { return .gitea }
        if h == "bitbucket.org" || h.hasSuffix(".bitbucket.org") { return .bitbucket }
        if url.contains("/src/branch/") || url.contains("/src/tag/") || url.contains("/src/commit/") { return .gitea }
        return .unknown
    }

    /// Parse any of the shapes a user actually pastes, for any supported forge:
    /// bare `owner/repo`, a repo URL, `/tree/<ref>/<dir>` (GitHub),
    /// `/-/tree/<ref>/<dir>` (GitLab), `/src/branch/<ref>/<dir>` (Gitea),
    /// `/src/<ref>/<dir>` (Bitbucket), and the matching `/blob/…` forms.
    static func parseRepoURL(_ rawURL: String, knownRefs: Set<String>? = nil) -> RepoRef? {
        var s = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        var origin = "https://github.com"
        if s.hasPrefix("http://") || s.hasPrefix("https://") {
            guard let schemeRange = s.range(of: "://") else { return nil }
            let afterScheme = s.index(schemeRange.upperBound, offsetBy: 0)
            let hostEnd = s[afterScheme...].firstIndex(of: "/") ?? s.endIndex
            origin = String(s[s.startIndex..<hostEnd])
            s = String(s[hostEnd...]).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        } else if s.hasPrefix("www.") {
            s = String(s.dropFirst(4))
        }
        if !rawURL.contains("://") {
            if s.hasPrefix("github.com/") {
                s = String(s.dropFirst("github.com/".count))
            } else if s.contains("."), let slash = s.firstIndex(of: "/"), s[s.startIndex..<slash].contains(".") {
                // A bare `gitlab.com/owner/repo` or `codeberg.org/owner/repo`.
                origin = "https://" + String(s[s.startIndex..<slash])
                s = String(s[s.index(after: slash)...])
            }
        }
        s = s.split(separator: "?").first.map(String.init) ?? s
        s = s.split(separator: "#").first.map(String.init) ?? s
        while s.hasSuffix("/") { s.removeLast() }
        guard !s.isEmpty else { return nil }
        let host = origin.contains("://") ? String(origin.split(separator: "/").dropFirst(2).first ?? "") : ""
        let provider = detectProvider(host: host, url: rawURL)
        // [T-android-repo-digest-percent-path] Segments came from a URL, so percent
        // escapes have to be decoded before they reach an API: a URL pasted straight out
        // of the browser's address bar carries `%20` for every space
        // ("preseed/First%20Run") while the API wants the real path ("preseed/First Run").
        // `removingPercentEncoding` leaves `+` alone, which is correct for a PATH —
        // GitHub has files named `chrome++.ini`, and a form decoder would read that plus
        // as a space. A malformed escape returns nil, so the raw segment is kept and URL
        // parsing still succeeds. Only the URL is decoded; the `path` argument is
        // already literal, which is why the repo-URL + path workaround always worked.
        let parts = s.split(separator: "/").map(String.init).filter { !$0.isEmpty }
            .map { $0.removingPercentEncoding ?? $0 }
        guard parts.count >= 2 else { return nil }
        let owner = parts[0]
        var repo = parts[1]
        if repo.hasSuffix(".git") { repo = String(repo.dropLast(4)) }
        guard !owner.isEmpty, !repo.isEmpty else { return nil }

        // GitLab marks its UI paths with `/-/`; drop it before reading ref/path.
        var rest = Array(parts.dropFirst(2))
        if rest.first == "-" { rest = Array(rest.dropFirst()) }
        var ref: String?
        var path: String?
        if rest.count >= 2, rest[0] == "tree" || rest[0] == "blob" {
            ref = rest[1]
            let tail = rest.dropFirst(2).joined(separator: "/")
            path = tail.isEmpty ? nil : tail
        } else if rest.count >= 3, rest[0] == "src", ["branch", "tag", "commit"].contains(rest[1]) {
            // Gitea: /src/branch/<branch>/<path>, /src/tag/<tag>/…, /src/commit/<sha>/…
            ref = rest[2]
            let tail = rest.dropFirst(3).joined(separator: "/")
            path = tail.isEmpty ? nil : tail
        } else if rest.count >= 2, rest[0] == "src" {
            // Bitbucket: /src/<ref>/<path> — the ref is the first segment, so a
            // branch containing a slash has to be passed via `ref=`.
            ref = rest[1]
            let tail = rest.dropFirst(2).joined(separator: "/")
            path = tail.isEmpty ? nil : tail
        } else {
            let tail = rest.joined(separator: "/")
            path = tail.isEmpty ? nil : tail
        }
        // A branch containing slashes is only resolvable against the real ref list.
        if let current = ref, let knownRefs {
            let candidate = ([current] + (path?.split(separator: "/").map(String.init) ?? [])).joined(separator: "/")
            if let match = knownRefs.filter({ candidate.hasPrefix($0) }).max(by: { $0.count < $1.count }) {
                ref = match
                let tail = String(candidate.dropFirst(match.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                path = tail.isEmpty ? nil : tail
            }
        }
        return RepoRef(owner: owner, repo: repo, ref: ref, path: path, origin: origin, provider: provider)
    }

    static func isBlobLike(_ rawURL: String) -> Bool {
        rawURL.contains("/blob/") || rawURL.contains("/-/blob/")
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

    // MARK: - providers

    /// One adapter per provider: the same three questions (what is the default
    /// branch, what is in the tree, what is in this file) asked the way that host
    /// wants them asked.
    private protocol Adapter {
        func defaultBranch(_ repo: RepoRef, token: String) async -> String?
        func listTree(_ repo: RepoRef, ref: String, token: String) async -> [TreeEntry]?
        func fetchRaw(_ repo: RepoRef, ref: String, path: String, token: String) async -> Data?
    }

    private static func adapterFor(_ provider: Provider) -> Adapter {
        switch provider {
        case .github: return GitHubAdapter()
        case .gitlab: return GitLabAdapter()
        case .gitea: return GiteaAdapter()
        case .bitbucket: return BitbucketAdapter()
        // Unresolved: use the shape the probe tried first, so the adapter and the
        // resolution cannot disagree about what the host turned out to be.
        case .unknown: return GiteaAdapter()
        }
    }

    /// Probe an unrecognised host: Gitea shape first, then GitLab.
    private static func resolveProvider(_ repo: RepoRef, token: String) async -> Provider {
        guard repo.provider == .unknown else { return repo.provider }
        if await GiteaAdapter().probe(repo, token: token) { return .gitea }
        if await GitLabAdapter().probe(repo, token: token) { return .gitlab }
        return .unknown
    }

    // MARK: - HTTP

    private static func request(
        _ url: String,
        token: String,
        accept: String,
        authHeader: String = "Authorization",
        authPrefix: String = "Bearer"
    ) -> URLRequest? {
        guard let parsed = URL(string: url) else { return nil }
        var request = URLRequest(url: parsed)
        request.timeoutInterval = 45
        request.setValue("OpenMinis/repo_digest", forHTTPHeaderField: "User-Agent")
        request.setValue(accept, forHTTPHeaderField: "Accept")
        if !token.isEmpty {
            request.setValue(authPrefix.isEmpty ? token : "\(authPrefix) \(token)", forHTTPHeaderField: authHeader)
        }
        return request
    }

    /// Hosts are arbitrary now, so the guard runs on every URL the tool builds.
    private static func get(
        _ url: String,
        token: String,
        accept: String,
        authHeader: String = "Authorization",
        authPrefix: String = "Bearer"
    ) async -> String? {
        if FetchUrlGuard.blockedReason(url) != nil { return nil }
        guard let request = request(url, token: token, accept: accept, authHeader: authHeader, authPrefix: authPrefix) else { return nil }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }

    private static func bytes(
        _ url: String,
        token: String,
        accept: String,
        authHeader: String = "Authorization",
        authPrefix: String = "Bearer"
    ) async -> Data? {
        if FetchUrlGuard.blockedReason(url) != nil { return nil }
        guard let request = request(url, token: token, accept: accept, authHeader: authHeader, authPrefix: authPrefix) else { return nil }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return data.count > RepoDigestTool.maxFileBytes ? data.prefix(RepoDigestTool.maxFileBytes) : data
        } catch {
            return nil
        }
    }

    private static func treeFromJSON(_ body: String) -> [TreeEntry]? {
        guard let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let array = root["tree"] as? [[String: Any]] else { return nil }
        return array.compactMap { item in
            let path = item["path"] as? String ?? ""
            guard !path.isEmpty else { return nil }
            return TreeEntry(path: path, type: item["type"] as? String ?? "", size: item["size"] as? Int ?? 0)
        }
    }

    /// GitHub: the shape this tool started as.
    private struct GitHubAdapter: Adapter {
        func defaultBranch(_ repo: RepoRef, token: String) async -> String? {
            guard let body = await RepoDigestTool.get("\(RepoDigestTool.apiRoot)/repos/\(repo.slug)", token: token, accept: "application/vnd.github+json"),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
            let branch = (root["default_branch"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return branch.isEmpty ? nil : branch
        }

        func listTree(_ repo: RepoRef, ref: String, token: String) async -> [TreeEntry]? {
            let url = "\(RepoDigestTool.apiRoot)/repos/\(repo.slug)/git/trees/\(RepoDigestTool.encodePath(ref))?recursive=1"
            guard let body = await RepoDigestTool.get(url, token: token, accept: "application/vnd.github+json") else { return nil }
            return RepoDigestTool.treeFromJSON(body)
        }

        func fetchRaw(_ repo: RepoRef, ref: String, path: String, token: String) async -> Data? {
            await RepoDigestTool.bytes("\(RepoDigestTool.rawRoot)/\(repo.slug)/\(RepoDigestTool.encodePath(ref))/\(RepoDigestTool.encodePath(path))", token: token, accept: "text/plain")
        }
    }

    /// GitLab (gitlab.com or self-hosted): the project id is the whole
    /// `namespace/project` path percent-encoded, which is why `encodeProjectPath`
    /// is separate from `encodePath`.
    private struct GitLabAdapter: Adapter {
        private let authHeader = "PRIVATE-TOKEN"

        func defaultBranch(_ repo: RepoRef, token: String) async -> String? {
            guard let body = await RepoDigestTool.get(projectURL(repo), token: token, accept: "application/json", authHeader: authHeader, authPrefix: ""),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
            let branch = (root["default_branch"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return branch.isEmpty ? nil : branch
        }

        func listTree(_ repo: RepoRef, ref: String, token: String) async -> [TreeEntry]? {
            var out: [TreeEntry] = []
            var page = 1
            // Paginated; a deep tree needs a handful of pages, and the cap keeps a
            // pathological one from spending the whole turn here.
            while page <= 10 {
                let url = "\(projectURL(repo))/repository/tree?recursive=true&per_page=100&page=\(page)&ref=\(RepoDigestTool.encodePath(ref))"
                guard let body = await RepoDigestTool.get(url, token: token, accept: "application/json", authHeader: authHeader, authPrefix: ""),
                      let data = body.data(using: .utf8),
                      let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { break }
                for item in array {
                    let path = item["path"] as? String ?? ""
                    guard !path.isEmpty else { continue }
                    let type = (item["type"] as? String ?? "") == "tree" ? "tree" : "blob"
                    out.append(TreeEntry(path: path, type: type, size: 0))
                }
                if array.count < 100 { break }
                page += 1
            }
            return out.isEmpty ? nil : out
        }

        func fetchRaw(_ repo: RepoRef, ref: String, path: String, token: String) async -> Data? {
            await RepoDigestTool.bytes(
                "\(projectURL(repo))/repository/files/\(RepoDigestTool.encodeProjectPath(path))/raw?ref=\(RepoDigestTool.encodePath(ref))",
                token: token,
                accept: "text/plain",
                authHeader: authHeader,
                authPrefix: ""
            )
        }

        private func projectURL(_ repo: RepoRef) -> String {
            "\(repo.origin)/api/v4/projects/\(RepoDigestTool.encodeProjectPath(repo.slug))"
        }

        /// Reachability + shape check for the unknown-host probe.
        func probe(_ repo: RepoRef, token: String) async -> Bool {
            await RepoDigestTool.get(projectURL(repo), token: token, accept: "application/json", authHeader: authHeader, authPrefix: "") != nil
        }
    }

    /// Gitea and the family sharing its API (Forgejo, Gogs, Codeberg). The API is
    /// deliberately GitHub-shaped, so the tree parse is shared; only the raw URL and
    /// the auth header differ.
    private struct GiteaAdapter: Adapter {
        func defaultBranch(_ repo: RepoRef, token: String) async -> String? {
            guard let body = await RepoDigestTool.get(repoURL(repo), token: token, accept: "application/json", authHeader: "Authorization", authPrefix: "token"),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
            let branch = (root["default_branch"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return branch.isEmpty ? nil : branch
        }

        func listTree(_ repo: RepoRef, ref: String, token: String) async -> [TreeEntry]? {
            var out: [TreeEntry] = []
            var page = 1
            while page <= 10 {
                let url = "\(repoURL(repo))/git/trees/\(RepoDigestTool.encodePath(ref))?recursive=true&per_page=100&page=\(page)"
                guard let body = await RepoDigestTool.get(url, token: token, accept: "application/json", authHeader: "Authorization", authPrefix: "token"),
                      let parsed = RepoDigestTool.treeFromJSON(body) else { break }
                out += parsed
                if parsed.count < 100 { break }
                page += 1
            }
            return out.isEmpty ? nil : out
        }

        func fetchRaw(_ repo: RepoRef, ref: String, path: String, token: String) async -> Data? {
            await RepoDigestTool.bytes(
                "\(repoURL(repo))/raw/\(RepoDigestTool.encodePath(path))?ref=\(RepoDigestTool.encodePath(ref))",
                token: token,
                accept: "text/plain",
                authHeader: "Authorization",
                authPrefix: "token"
            )
        }

        private func repoURL(_ repo: RepoRef) -> String { "\(repo.origin)/api/v1/repos/\(repo.slug)" }

        func probe(_ repo: RepoRef, token: String) async -> Bool {
            await RepoDigestTool.get(repoURL(repo), token: token, accept: "application/json", authHeader: "Authorization", authPrefix: "token") != nil
        }
    }

    /// Bitbucket Cloud: no recursive listing at all, so the tree is walked one
    /// directory per page (bounded), and the raw endpoint is the same `src` URL the
    /// web UI uses.
    private struct BitbucketAdapter: Adapter {
        private let pageLimit = 20

        func defaultBranch(_ repo: RepoRef, token: String) async -> String? {
            guard let body = await RepoDigestTool.get(apiURL(repo), token: token, accept: "application/json"),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
            let name = ((root["mainbranch"] as? [String: Any])?["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return name.isEmpty ? nil : name
        }

        func listTree(_ repo: RepoRef, ref: String, token: String) async -> [TreeEntry]? {
            var out: [TreeEntry] = []
            var queue: [String] = [""]
            var calls = 0
            while !queue.isEmpty, calls < pageLimit {
                let dir = queue.removeFirst()
                var next: String? = "\(apiURL(repo))/src/\(RepoDigestTool.encodePath(ref))/\(dir)?pagelen=100"
                while let current = next, calls < pageLimit, out.count < 5_000 {
                    guard let body = await RepoDigestTool.get(current, token: token, accept: "application/json"),
                          let data = body.data(using: .utf8),
                          let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                        return out.isEmpty ? nil : out
                    }
                    calls += 1
                    for item in (root["values"] as? [[String: Any]] ?? []) {
                        let path = item["path"] as? String ?? ""
                        guard !path.isEmpty else { continue }
                        if (item["type"] as? String ?? "") == "commit_directory" {
                            queue.append("\(path)/")
                        } else {
                            out.append(TreeEntry(path: path, type: "blob", size: item["size"] as? Int ?? 0))
                        }
                    }
                    let following = (root["next"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    next = following.isEmpty ? nil : following
                }
            }
            return out.isEmpty ? nil : out
        }

        func fetchRaw(_ repo: RepoRef, ref: String, path: String, token: String) async -> Data? {
            await RepoDigestTool.bytes("\(apiURL(repo))/src/\(RepoDigestTool.encodePath(ref))/\(RepoDigestTool.encodePath(path))", token: token, accept: "text/plain")
        }

        private func apiURL(_ repo: RepoRef) -> String {
            "https://api.bitbucket.org/2.0/repositories/\(repo.owner)/\(repo.repo)"
        }
    }

    /// `group/project` → `group%2Fproject` (GitLab's project id).
    static func encodeProjectPath(_ raw: String) -> String {
        raw.addingPercentEncoding(withAllowedCharacters: RepoDigestTool.pathSegmentAllowed) ?? raw
    }
}
