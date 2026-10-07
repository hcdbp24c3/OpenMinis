package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.gitvault.GitVault
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [T-android-repo-digest] Read a git repository as a digest — file tree plus the
 * contents that matter — in ONE tool call, for the hosts gitingest supports and
 * not just GitHub.
 *
 * Why this exists: given a repo link, the alternatives were
 *
 *  - `browser_use`, which reads one page at a time and cannot follow a tree; and
 *  - cloning it into the sandbox, which costs a full checkout (and a network round
 *    trip per object) before the first useful byte, then leaves the agent grep-ing
 *    a filesystem.
 *
 * Each host is read through its OWN API, because that is one request for the tree
 * and no API quota for the file bodies:
 *
 *  - **GitHub** — `api.github.com/repos/…/git/trees` + `raw.githubusercontent.com`
 *  - **GitLab** (gitlab.com and self-hosted) — `/api/v4/projects/<path>/repository/tree`
 *    + `/repository/files/<path>/raw`
 *  - **Gitea / Forgejo / Gogs / Codeberg** — `/api/v1/repos/…/git/trees` + `/raw/…`
 *    (Gitea's API is deliberately GitHub-shaped, so one adapter covers the family)
 *  - **Bitbucket Cloud** — `api.bitbucket.org/2.0/repositories/…/src/…` (walked: it
 *    has no recursive listing)
 *  - **anything else** — the Gitea-shaped API is probed first (most self-hosted
 *    forges are Gitea/Forgejo/Gogs), then the GitLab shape; when neither answers,
 *    the tool says so and points at `shell_execute` (`git clone --depth 1`) or
 *    `web_fetch` rather than pretending the host is unsupported by design.
 *
 * Hosts are now arbitrary, so the SSRF guard runs on EVERY url this tool builds
 * (origin included): private, loopback, link-local and metadata addresses stay
 * unreachable even though the model chooses the repository.
 */
object RepoDigestTool {
    const val NAME = "repo_digest"

    private const val API_ROOT = "https://api.github.com"
    private const val RAW_ROOT = "https://raw.githubusercontent.com"
    private const val DEFAULT_MAX_FILES = 40
    private const val HARD_MAX_FILES = 200
    private const val DEFAULT_MAX_CHARS = 60_000
    private const val HARD_MAX_CHARS = 200_000
    private const val MAX_FILE_BYTES = 300_000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(FetchUrlGuard.publicInternetDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Git hosts the tool reads through their own API. */
    enum class Provider(val label: String) {
        GITHUB("GitHub"),
        GITLAB("GitLab"),
        GITEA("Gitea/Forgejo/Codeberg"),
        BITBUCKET("Bitbucket Cloud"),
        UNKNOWN("unknown host"),
    }

    /**
     * owner/repo/ref/path lifted out of any of the URL shapes a forge uses, plus
     * the origin and provider they were read from.
     *
     * [origin] is kept so every later request is built against the host the user
     * actually named — which is also why the SSRF guard runs per request rather
     * than once on the input URL: a self-hosted forge may live anywhere the guard
     * allows, and nowhere it does not.
     */
    data class RepoRef(
        val owner: String,
        val repo: String,
        val ref: String?,
        val path: String?,
        val origin: String = "https://github.com",
        val provider: Provider = Provider.GITHUB,
    ) {
        val slug: String get() = "$owner/$repo"
    }

    /** One blob from a forge's tree listing. Internal so the rendering tests can build one. */
    internal data class TreeEntry(val path: String, val type: String, val size: Int)

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a git repository as a digest: the file tree plus the contents of the files " +
            "that matter, in one call — like gitingest. Use this instead of cloning the repo or browsing it " +
            "page by page. Works with GitHub, GitLab (including self-hosted), Gitea/Forgejo/Codeberg/Gogs, " +
            "Bitbucket Cloud, and probes other hosts with both API shapes before giving up. Understands repo, " +
            "`/tree/<ref>/<dir>`, `/-/tree/<ref>/<dir>`, `/src/branch/<ref>/<dir>` and the matching blob URLs; " +
            "`format=tree` lists files only (cheapest way to find your way around), `format=files` (default) " +
            "adds contents, `format=file` returns one file. Binary, vendored, minified and lock files are " +
            "skipped automatically; `include`/`exclude` globs narrow that further. Public repos need no " +
            "credentials; tokens configured in Settings › Repository digest raise the API limits and reach " +
            "private repos.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Digest fastapi repo')."),
            "url" to AgentToolParam("string", "Repository, directory or file URL — any supported host — or 'owner/repo' for GitHub."),
            "ref" to AgentToolParam("string", "Branch, tag or commit SHA (default: the repo's default branch, or the one in the URL). Required for a branch whose name contains a slash on hosts that put the ref in the path (Bitbucket)."),
            "path" to AgentToolParam("string", "Restrict to this directory or file path inside the repo."),
            "include" to AgentToolParam("string", "Comma-separated globs that files must match (e.g. '*.kt,src/**')."),
            "exclude" to AgentToolParam("string", "Comma-separated globs to skip (e.g. '**/test/**,docs/**')."),
            "format" to AgentToolParam(
                "string",
                "tree = file list only; files (default) = tree + contents; file = a single file's contents.",
                enumValues = listOf("tree", "files", "file"),
            ),
            "max_files" to AgentToolParam("integer", "How many files to include (default 40, maximum 200)."),
            "max_chars" to AgentToolParam("integer", "Total character budget for the digest (default 60000, maximum 200000)."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url", "ref", "path", "include", "exclude", "format"),
    )

    suspend fun execute(argsJson: String, context: Context? = null): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return ToolExecutionResult("repo_digest: invalid JSON", false, toolTitle = NAME)
        }
        val toolTitle = args.optString("tool_title", NAME)
        val repo = parseRepoUrl(args.optString("url", ""))
            ?: return ToolExecutionResult(
                "repo_digest: expected a repository, /tree/…, /-/tree/…, /src/branch/… or /blob/… URL (or owner/repo)",
                false,
                toolTitle = toolTitle,
            )
        val explicitRef = args.optString("ref", "").trim()
        val pathArg = args.optString("path", "").trim()
        val format = args.optString("format", "files").lowercase().ifBlank { "files" }
        val maxFiles = args.optInt("max_files", DEFAULT_MAX_FILES).coerceIn(1, HARD_MAX_FILES)
        val maxChars = args.optInt("max_chars", DEFAULT_MAX_CHARS).coerceIn(500, HARD_MAX_CHARS)
        val include = parseGlobs(args.optString("include", ""))
        val exclude = parseGlobs(args.optString("exclude", ""))
        // Credentials come from the git vault, matched on the HOST the URL names —
        // not on the forge family — so a company GitLab and gitlab.com carry
        // different tokens and a fleet of self-hosted forges needs no code change.
        // The header shape stays provider-driven (see [adapterFor]); the vault only
        // supplies the secret. Resolved once: identifying the provider does not
        // change the host, so a probe never needs a second lookup.
        val host = GitVault.normalizeHost(repo.origin)
        val token = GitVault.tokenFor(context, host)

        return try {
            // Unknown hosts are probed once, here: Gitea's API first (most
            // self-hosted forges are Gitea/Forgejo/Gogs), then GitLab's. Probing per
            // FILE would multiply the cost of every fetch, so it is resolved up front.
            val provider = resolveProvider(repo, token)
            val target = repo.copy(provider = provider)
            if (provider == Provider.UNKNOWN) {
                return ToolExecutionResult(
                    "repo_digest: ${target.origin} did not answer either the Gitea or the GitLab API. " +
                        "If it is a plain git server, clone it with shell_execute " +
                        "(`git clone --depth 1 <url>`), or fetch a single file with web_fetch.",
                    false,
                    toolTitle = toolTitle,
                )
            }
            val adapter = adapterFor(provider)

            // `HEAD` is the last resort: an unauthenticated host may refuse the repo
            // metadata call while still serving an explicit ref, and "the default
            // branch" is what every forge understands `HEAD` to mean.
            val ref = explicitRef.ifBlank { repo.ref.orEmpty() }
                .ifBlank { adapter.defaultBranch(target, token) ?: "HEAD" }
            val wantedPath = pathArg.ifBlank { repo.path.orEmpty() }
            val wantSingleFile = format == "file" || isBlobLike(args.optString("url", ""))

            if (wantSingleFile && wantedPath.isNotEmpty()) {
                val content = adapter.fetchRaw(target, ref, wantedPath, token)
                    ?: return ToolExecutionResult(
                        "repo_digest: could not read $wantedPath at $ref on ${provider.label} " +
                            "(wrong path, or private repo without a token?)",
                        false,
                        toolTitle = toolTitle,
                    )
                val text = String(content, Charsets.UTF_8)
                return ToolExecutionResult(
                    buildString {
                        appendLine("Repo: ${target.origin}/${target.slug} @ $ref")
                        appendLine("File: $wantedPath (${content.size} bytes)")
                        appendLine()
                        append(sanitize(text, maxChars))
                    },
                    true,
                    toolTitle = toolTitle,
                )
            }

            val entries = adapter.listTree(target, ref, token)
                ?: return ToolExecutionResult(
                    "repo_digest: ${provider.label} did not return a tree for ${target.slug} @ $ref " +
                        "(rate limit, wrong ref, or private repo without a token?)",
                    false,
                    toolTitle = toolTitle,
                )

            val matching = entries.filter { entry ->
                entry.type != "tree" &&
                    (wantedPath.isEmpty() || entry.path == wantedPath || entry.path.startsWith("$wantedPath/")) &&
                    matchesGlobs(entry.path, include, exclude) &&
                    !shouldSkip(entry.path)
            }.sortedBy { it.path }

            // A host that reports no size (GitLab, Bitbucket directories) reports 0:
            // it passes the size filter and the read itself is what caps the bytes.
            val fetchable = matching.filter { it.size <= MAX_FILE_BYTES }
            val tooBig = matching.size - fetchable.size
            val selected = if (format == "tree") emptyList() else fetchable.take(maxFiles)
            val notFetched = fetchable.size - selected.size

            val contents = if (selected.isEmpty()) {
                emptyMap()
            } else {
                coroutineScope {
                    val gate = Semaphore(6)
                    selected.map { entry ->
                        async {
                            gate.withPermit {
                                entry.path to adapter.fetchRaw(target, ref, entry.path, token)
                            }
                        }
                    }.awaitAll().toMap()
                }
            }

            val treeLine = "Repo: ${target.origin}/${target.slug} @ $ref (${provider.label})" +
                (if (wantedPath.isNotEmpty()) " (path: $wantedPath)" else "") +
                "\nFiles: ${matching.size} matched" +
                (if (format == "tree") "" else ", ${selected.size} included") +
                buildOmissionNote(notFetched, tooBig, maxFiles)

            val body = buildString {
                appendLine(treeLine)
                appendLine()
                appendLine("Tree (${matching.size}):")
                append(treeText(matching, maxChars / 3))
                if (format != "tree") {
                    appendLine()
                    appendLine()
                    for (entry in selected) {
                        val bytes = contents[entry.path] ?: continue
                        val text = String(bytes, Charsets.UTF_8)
                        appendLine("=== ${entry.path} (${bytes.size} bytes) ===")
                        val remaining = maxChars - length
                        if (remaining <= 200) {
                            appendLine("[budget exhausted — remaining files omitted; raise max_chars or narrow the path]")
                            break
                        }
                        appendLine(sanitize(text, minOf(remaining - 100, MAX_FILE_BYTES)))
                        appendLine()
                    }
                }
            }
            ToolExecutionResult(body.trim(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult(
                "repo_digest failed for ${repo.slug}: ${e.message ?: e.javaClass.simpleName}",
                false,
                toolTitle = toolTitle,
            )
        }
    }

    // ── URL shapes and host detection ───────────────────────────────────────

    /**
     * Provider detection from the host, then from the URL's own shape. The shape
     * matters for self-hosted instances: `/-/tree/` is GitLab's signature (stock
     * GitLab, or GitLab CE on a company domain), `/src/branch/` is Gitea's, and both
     * are far more reliable than trying to recognise hostnames.
     */
    internal fun detectProvider(host: String, url: String): Provider {
        val h = host.lowercase()
        return when {
            h == "github.com" || h.endsWith(".github.com") -> Provider.GITHUB
            h == "gitlab.com" || h.endsWith(".gitlab.com") || url.contains("/-/") -> Provider.GITLAB
            h == "codeberg.org" || h.contains("gitea") || h.contains("forgejo") || h.contains("gogs") -> Provider.GITEA
            h == "bitbucket.org" || h.endsWith(".bitbucket.org") -> Provider.BITBUCKET
            url.contains("/src/branch/") || url.contains("/src/tag/") || url.contains("/src/commit/") -> Provider.GITEA
            else -> Provider.UNKNOWN
        }
    }

    /**
     * Parse any of the shapes a user actually pastes, for any supported forge: bare
     * `owner/repo`, a repo URL, `/tree/<ref>/<dir>` (GitHub), `/-/tree/<ref>/<dir>`
     * (GitLab), `/src/branch/<ref>/<dir>` (Gitea), `/src/<ref>/<dir>` (Bitbucket),
     * and the matching `/blob/…` forms.
     */
    internal fun parseRepoUrl(rawUrl: String, knownRefs: Set<String>? = null): RepoRef? {
        var s = rawUrl.trim()
        if (s.isEmpty()) return null
        var origin = "https://github.com"
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val schemeEnd = s.indexOf("://") + 3
            val hostEnd = s.indexOf('/', schemeEnd).let { if (it < 0) s.length else it }
            origin = s.substring(0, hostEnd)
            s = s.substring(hostEnd).trimStart('/')
        } else {
            s = s.removePrefix("www.")
            if (s.startsWith("github.com/")) {
                s = s.removePrefix("github.com/")
            } else if (!s.contains("://") && s.contains('.') && s.contains('/')) {
                // A bare `gitlab.com/owner/repo` or `codeberg.org/owner/repo`.
                val hostEnd = s.indexOf('/')
                origin = "https://${s.substring(0, hostEnd)}"
                s = s.substring(hostEnd).trimStart('/')
            }
        }
        s = s.substringBefore('?').substringBefore('#').trimEnd('/')
        if (s.isEmpty()) return null
        val host = origin.substringAfter("://").substringBefore('/')
        val provider = detectProvider(host, rawUrl)
        // Every segment here came from a URL, so percent-escapes have to be decoded
        // before they reach an API: a URL pasted straight out of the browser's address
        // bar carries `%20` for every space ("preseed/First%20Run"), and the GitHub
        // Contents API wants the real path ("preseed/First Run"). Only the URL is
        // decoded — the `path` argument is already literal, which is why the documented
        // workaround (repo URL + `path` override) always worked.
        val parts = s.split('/').filter { it.isNotEmpty() }.map { decodeUrlSegment(it) }
        if (parts.size < 2) return null
        val owner = parts[0]
        var repo = parts[1].removeSuffix(".git")
        if (owner.isEmpty() || repo.isEmpty()) return null

        // GitLab marks its UI paths with `/-/`; drop it before reading ref/path.
        val rest = parts.drop(2).let { if (it.firstOrNull() == "-") it.drop(1) else it }
        var ref: String? = null
        var path: String? = null
        when {
            rest.size >= 2 && (rest[0] == "tree" || rest[0] == "blob") -> {
                ref = rest[1]
                path = rest.drop(2).joinToString("/").ifEmpty { null }
            }
            // Gitea: /src/branch/<branch>/<path>, /src/tag/<tag>/…, /src/commit/<sha>/…
            // Provider-gated, because `/src/` is an ordinary DIRECTORY on GitHub and
            // GitLab: without the gate, `github.com/o/r/src/main` would be read as
            // "branch main" instead of "the path src/main".
            rest.size >= 3 && rest[0] == "src" && rest[1] in listOf("branch", "tag", "commit") &&
                provider == Provider.GITEA -> {
                ref = rest[2]
                path = rest.drop(3).joinToString("/").ifEmpty { null }
            }
            // Bitbucket: /src/<ref>/<path> — same gate, same reason; and the ref is
            // the first segment, so a branch containing a slash goes via `ref=`.
            rest.size >= 2 && rest[0] == "src" && provider == Provider.BITBUCKET -> {
                ref = rest[1]
                path = rest.drop(2).joinToString("/").ifEmpty { null }
            }
            else -> path = rest.joinToString("/").ifEmpty { null }
        }
        // A branch containing slashes is only resolvable against the real ref list.
        if (ref != null && knownRefs != null) {
            val candidate = (listOf(ref) + (path?.split('/') ?: emptyList())).joinToString("/")
            val match = knownRefs.filter { candidate.startsWith(it) }.maxByOrNull { it.length }
            if (match != null) {
                ref = match
                path = candidate.removePrefix(match).trimStart('/').ifEmpty { null }
            }
        }
        return RepoRef(owner, repo, ref, path, origin, provider)
    }

    internal fun isBlobLike(rawUrl: String): Boolean =
        rawUrl.contains("/blob/") || rawUrl.contains("/-/blob/")

    /**
     * [T-android-repo-digest-percent-path] Percent-decode one URL path segment.
     *
     * Two details that a plain `URLDecoder.decode` gets wrong for a PATH:
     *  - `+` is a literal plus in a URL path (it means space only in a
     *    `application/x-www-form-urlencoded` query) — GitHub has files named
     *    `chrome++.ini`, and decoding their `+` to a space would 404 them. The `+` is
     *    escaped before decoding so it survives;
     *  - a malformed escape (`%ZZ`, a lone trailing `%`) must not throw out of URL
     *    parsing: the raw segment is returned instead, so a weird URL still resolves
     *    the same way it did before this existed.
     */
    internal fun decodeUrlSegment(segment: String): String {
        if ('%' !in segment) return segment
        return runCatching {
            java.net.URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(segment)
    }

    // ── pure helpers (unit-tested) ──────────────────────────────────────────

    internal fun parseGlobs(raw: String): List<String> =
        raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    internal fun matchesGlobs(path: String, include: List<String>, exclude: List<String>): Boolean {
        if (exclude.any { globMatches(it, path) }) return false
        if (include.isEmpty()) return true
        return include.any { globMatches(it, path) }
    }

    /** `*` within a segment, `**` across segments, `?` one character. */
    internal fun globMatches(glob: String, path: String): Boolean {
        val regex = buildString {
            append('^')
            var i = 0
            while (i < glob.length) {
                val ch = glob[i]
                when {
                    ch == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        // `**/` may match zero segments, so `a/**/b` also matches `a/b`.
                        if (i + 2 < glob.length && glob[i + 2] == '/') {
                            append("(?:.*/)?")
                            i += 3
                            continue
                        }
                        append(".*")
                        i += 2
                        continue
                    }
                    ch == '*' -> append("[^/]*")
                    ch == '?' -> append("[^/]")
                    ch in ".+()|[]{}^$\\" -> append('\\').append(ch)
                    else -> append(ch)
                }
                i++
            }
            append('$')
        }
        return Regex(regex).matches(path)
    }

    /**
     * Files not worth spending the budget on: binaries (they would arrive as
     * mojibake), vendored trees and build output (thousands of files that are not
     * the project), minified bundles, and lock files (megabytes of checksums).
     */
    internal fun shouldSkip(path: String): Boolean {
        val lower = path.lowercase()
        val segments = lower.split('/')
        if (segments.any { it in SKIPPED_DIRS }) return true
        val name = segments.last()
        if (name in SKIPPED_FILES) return true
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css")) return true
        if (name.startsWith("package-lock") || name.startsWith("yarn.lock") || name == "pnpm-lock.yaml") return true
        val ext = name.substringAfterLast('.', "")
        return ext in BINARY_EXTENSIONS
    }

    private val SKIPPED_DIRS = setOf(
        ".git", "node_modules", "vendor", "dist", "build", "out", "target",
        "__pycache__", ".venv", "venv", "site-packages", ".idea", ".gradle",
        "pods", "deriveddata", ".next", ".nuxt", "coverage", ".mypy_cache",
    )

    private val SKIPPED_FILES = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock",
        "cargo.lock", "gemfile.lock", "composer.lock", "go.sum",
    )

    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tif", "tiff", "svgz",
        "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "apk",
        "aar", "so", "dylib", "dll", "exe", "bin", "o", "a", "class", "pyc",
        "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "mov", "avi", "wav",
        "flac", "ogg", "webm", "heic", "psd", "sketch", "xlsx", "xls", "docx",
        "doc", "pptx", "ppt", "db", "sqlite", "sqlite3", "onnx", "tflite", "pb",
        "wasm", "keystore", "jks", "p12", "der", "pem", "lock",
    )

    /** Compact tree: one path per line, with byte size, so it stays cheap. */
    internal fun treeText(entries: List<TreeEntry>, budget: Int): String {
        val out = StringBuilder()
        for (entry in entries) {
            val line = "${entry.path} (${entry.size})\n"
            if (out.length + line.length > budget) {
                out.append("[tree truncated: ${entries.size} entries total]\n")
                break
            }
            out.append(line)
        }
        return out.toString().trimEnd()
    }

    internal fun buildOmissionNote(notFetched: Int, tooBig: Int, maxFiles: Int): String {
        val notes = mutableListOf<String>()
        if (notFetched > 0) notes += "$notFetched beyond the $maxFiles-file cap"
        if (tooBig > 0) notes += "$tooBig larger than ${MAX_FILE_BYTES / 1000}KB"
        if (notes.isEmpty()) return ""
        return "\nOmitted: ${notes.joinToString(", ")} — raise max_files or narrow path/include."
    }

    internal fun sanitize(text: String, maxChars: Int): String {
        val withoutNul = if (text.contains('\u0000')) text.replace("\u0000", "") else text
        if (withoutNul.length <= maxChars) return withoutNul
        return withoutNul.take(maxChars) + "\n… [file truncated at $maxChars chars]"
    }

    /** Path segments are percent-encoded but slashes stay: refs and paths contain them. */
    internal fun encodePath(raw: String): String =
        raw.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    /** `group/project` → `group%2Fproject` (GitLab's project id). */
    internal fun encodeProjectPath(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    // ── providers ───────────────────────────────────────────────────────────

    /**
     * One adapter per provider: the same three questions (what is the default
     * branch, what is in the tree, what is in this file) asked the way that host
     * wants them asked.
     */
    private interface Adapter {
        suspend fun defaultBranch(repo: RepoRef, token: String): String?
        suspend fun listTree(repo: RepoRef, ref: String, token: String): List<TreeEntry>?
        suspend fun fetchRaw(repo: RepoRef, ref: String, path: String, token: String): ByteArray?
    }

    private fun adapterFor(provider: Provider): Adapter = when (provider) {
        Provider.GITHUB -> GitHubAdapter
        Provider.GITLAB -> GitLabAdapter
        Provider.GITEA -> GiteaAdapter
        Provider.BITBUCKET -> BitbucketAdapter
        // Unresolved: use the shape the probe tried first, so the adapter and the
        // resolution cannot disagree about what the host turned out to be.
        Provider.UNKNOWN -> GiteaAdapter
    }

    /** Probe an unrecognised host: Gitea shape first, then GitLab. */
    private suspend fun resolveProvider(repo: RepoRef, token: String): Provider {
        if (repo.provider != Provider.UNKNOWN) return repo.provider
        if (GiteaAdapter.probe(repo, token)) return Provider.GITEA
        if (GitLabAdapter.probe(repo, token)) return Provider.GITLAB
        return Provider.UNKNOWN
    }

    private fun request(
        url: String,
        token: String,
        accept: String,
        authHeader: String = "Authorization",
        authPrefix: String = "Bearer",
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenMinis/repo_digest")
            .header("Accept", accept)
        if (token.isNotBlank()) builder.header(authHeader, if (authPrefix.isEmpty()) token else "$authPrefix $token")
        return builder
    }

    private fun get(
        url: String,
        token: String,
        accept: String,
        authHeader: String = "Authorization",
        authPrefix: String = "Bearer",
    ): String? {
        // Hosts are arbitrary now, so the guard runs on every URL we build.
        FetchUrlGuard.blockedReason(url)?.let { return null }
        return try {
            client.newCall(request(url, token, accept, authHeader, authPrefix).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bytes(
        url: String,
        token: String,
        accept: String,
        authHeader: String = "Authorization",
        authPrefix: String = "Bearer",
    ): ByteArray? {
        FetchUrlGuard.blockedReason(url)?.let { return null }
        return try {
            client.newCall(request(url, token, accept, authHeader, authPrefix).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                // [T-http-body-read] Was readByteArray(contentLength): a chunked or
                // compressed raw response reports -1, which coerced to 0 and silently
                // returned an EMPTY file. Drain up to the cap instead.
                HttpBodyReader.readCapped(body, MAX_FILE_BYTES)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun treeFromJson(body: String): List<TreeEntry>? {
        val array = try {
            JSONObject(body).optJSONArray("tree") ?: return null
        } catch (_: Exception) {
            return null
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val path = item.optString("path")
                if (path.isEmpty()) continue
                add(TreeEntry(path, item.optString("type"), item.optInt("size", 0)))
            }
        }
    }

    /** GitHub: the shape this tool started as. */
    private object GitHubAdapter : Adapter {
        override suspend fun defaultBranch(repo: RepoRef, token: String): String? {
            val body = get("$API_ROOT/repos/${repo.slug}", token, "application/vnd.github+json") ?: return null
            return try {
                JSONObject(body).optString("default_branch").ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }

        override suspend fun listTree(repo: RepoRef, ref: String, token: String): List<TreeEntry>? =
            get("$API_ROOT/repos/${repo.slug}/git/trees/${encodePath(ref)}?recursive=1", token, "application/vnd.github+json")
                ?.let(::treeFromJson)

        override suspend fun fetchRaw(repo: RepoRef, ref: String, path: String, token: String): ByteArray? =
            bytes("$RAW_ROOT/${repo.slug}/${encodePath(ref)}/${encodePath(path)}", token, "text/plain")
    }

    /**
     * GitLab (gitlab.com or self-hosted): the project id is the whole
     * `namespace/project` path percent-encoded, which is why [encodeProjectPath] is
     * separate from [encodePath].
     */
    private object GitLabAdapter : Adapter {
        private const val AUTH_HEADER = "PRIVATE-TOKEN"

        override suspend fun defaultBranch(repo: RepoRef, token: String): String? {
            val body = get(projectUrl(repo), token, "application/json", authHeader = AUTH_HEADER, authPrefix = "") ?: return null
            return try {
                JSONObject(body).optString("default_branch").ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }

        override suspend fun listTree(repo: RepoRef, ref: String, token: String): List<TreeEntry>? {
            val out = mutableListOf<TreeEntry>()
            var page = 1
            // Paginated; a deep tree needs a handful of pages, and the cap keeps a
            // pathological one from spending the whole turn here.
            while (page <= 10) {
                val url = "${projectUrl(repo)}/repository/tree?recursive=true&per_page=100&page=$page&ref=${encodePath(ref)}"
                val body = get(url, token, "application/json", authHeader = AUTH_HEADER, authPrefix = "") ?: break
                val array = try {
                    JSONArray(body)
                } catch (_: Exception) {
                    break
                }
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val path = item.optString("path")
                    if (path.isEmpty()) continue
                    out.add(TreeEntry(path, if (item.optString("type") == "tree") "tree" else "blob", 0))
                }
                if (array.length() < 100) break
                page++
            }
            return out.ifEmpty { null }
        }

        override suspend fun fetchRaw(repo: RepoRef, ref: String, path: String, token: String): ByteArray? =
            bytes(
                "${projectUrl(repo)}/repository/files/${encodeProjectPath(path)}/raw?ref=${encodePath(ref)}",
                token,
                "text/plain",
                authHeader = AUTH_HEADER,
                authPrefix = "",
            )

        private fun projectUrl(repo: RepoRef): String =
            "${repo.origin}/api/v4/projects/${encodeProjectPath(repo.slug)}"

        /** Reachability + shape check for the unknown-host probe. */
        suspend fun probe(repo: RepoRef, token: String): Boolean =
            get(projectUrl(repo), token, "application/json", authHeader = AUTH_HEADER, authPrefix = "") != null
    }

    /**
     * Gitea and the family sharing its API (Forgejo, Gogs, Codeberg). The API is
     * deliberately GitHub-shaped, so the tree parse is shared; only the raw URL and
     * the auth header differ.
     */
    private object GiteaAdapter : Adapter {
        override suspend fun defaultBranch(repo: RepoRef, token: String): String? {
            val body = get(repoUrl(repo), token, "application/json", authHeader = "Authorization", authPrefix = "token")
                ?: return null
            return try {
                JSONObject(body).optString("default_branch").ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }

        override suspend fun listTree(repo: RepoRef, ref: String, token: String): List<TreeEntry>? {
            val out = mutableListOf<TreeEntry>()
            var page = 1
            while (page <= 10) {
                val body = get(
                    "${repoUrl(repo)}/git/trees/${encodePath(ref)}?recursive=true&per_page=100&page=$page",
                    token,
                    "application/json",
                    authHeader = "Authorization",
                    authPrefix = "token",
                ) ?: break
                val parsed = treeFromJson(body) ?: break
                out += parsed
                if (parsed.size < 100) break
                page++
            }
            return out.ifEmpty { null }
        }

        override suspend fun fetchRaw(repo: RepoRef, ref: String, path: String, token: String): ByteArray? =
            bytes(
                "${repoUrl(repo)}/raw/${encodePath(path)}?ref=${encodePath(ref)}",
                token,
                "text/plain",
                authHeader = "Authorization",
                authPrefix = "token",
            )

        private fun repoUrl(repo: RepoRef): String = "${repo.origin}/api/v1/repos/${repo.slug}"

        suspend fun probe(repo: RepoRef, token: String): Boolean =
            get(repoUrl(repo), token, "application/json", authHeader = "Authorization", authPrefix = "token") != null
    }

    /**
     * Bitbucket Cloud: no recursive listing at all, so the tree is walked one
     * directory per page (bounded), and the raw endpoint is the same `src` URL the
     * web UI uses.
     */
    private object BitbucketAdapter : Adapter {
        private const val PAGE_LIMIT = 20

        override suspend fun defaultBranch(repo: RepoRef, token: String): String? {
            val body = get(apiUrl(repo), token, "application/json") ?: return null
            return try {
                JSONObject(body).optJSONObject("mainbranch")?.optString("name")?.ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }

        override suspend fun listTree(repo: RepoRef, ref: String, token: String): List<TreeEntry>? {
            val out = mutableListOf<TreeEntry>()
            val queue = ArrayDeque(listOf(""))
            var calls = 0
            while (queue.isNotEmpty() && calls < PAGE_LIMIT) {
                val dir = queue.removeFirst()
                var next: String? = "${apiUrl(repo)}/src/${encodePath(ref)}/$dir?pagelen=100"
                while (next != null && calls < PAGE_LIMIT && out.size < 5_000) {
                    val body = get(next, token, "application/json") ?: return out.ifEmpty { null }
                    calls++
                    val root = try {
                        JSONObject(body)
                    } catch (_: Exception) {
                        return out.ifEmpty { null }
                    }
                    val values = root.optJSONArray("values") ?: JSONArray()
                    for (i in 0 until values.length()) {
                        val item = values.optJSONObject(i) ?: continue
                        val path = item.optString("path")
                        if (path.isEmpty()) continue
                        if (item.optString("type") == "commit_directory") {
                            queue.add("$path/")
                        } else {
                            out.add(TreeEntry(path, "blob", item.optInt("size", 0)))
                        }
                    }
                    next = root.optString("next").ifBlank { null }
                }
            }
            return out.ifEmpty { null }
        }

        override suspend fun fetchRaw(repo: RepoRef, ref: String, path: String, token: String): ByteArray? =
            bytes("${apiUrl(repo)}/src/${encodePath(ref)}/${encodePath(path)}", token, "text/plain")

        private fun apiUrl(repo: RepoRef): String =
            "https://api.bitbucket.org/2.0/repositories/${repo.owner}/${repo.repo}"
    }
}
