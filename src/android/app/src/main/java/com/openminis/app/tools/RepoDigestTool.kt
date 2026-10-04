package com.openminis.app.tools

import android.content.Context
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
 * [T-android-repo-digest] Read a GitHub repository the way gitingest does —
 * a file tree plus the contents of the files that matter — in ONE tool call.
 *
 * Why this exists: given a repo link, the alternatives were
 *
 *  - `browser_use`, which reads one page at a time and cannot follow a tree; and
 *  - cloning it into the sandbox, which costs a full checkout (and a network
 *    round trip per object) before the first useful byte, then leaves the agent
 *    grep-ing a filesystem.
 *
 * This asks GitHub what the tree IS (one API call), fetches only the files that
 * pass the filters from `raw.githubusercontent.com` (no API quota), and hands the
 * model a digest with explicit caps and an omitted-files note.
 *
 * Host allowlist: only github.com / api.github.com / raw.githubusercontent.com.
 * The tool is not a general proxy — `web_fetch` is that, with its own SSRF guard.
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

    /** owner/repo/ref/path lifted out of any of the URL shapes GitHub uses. */
    data class RepoRef(
        val owner: String,
        val repo: String,
        val ref: String?,
        val path: String?,
    ) {
        val slug: String get() = "$owner/$repo"
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a GitHub repository as a digest: the file tree plus the contents of the " +
            "files that matter, in one call — like gitingest. Use this instead of cloning the repo or " +
            "browsing it page by page. Understands repo, /tree/<branch>/<dir> and /blob/<branch>/<file> " +
            "URLs; `format=tree` lists files only (cheapest way to find your way around), `format=files` " +
            "(default) adds contents, `format=file` returns one file. Binary, vendored, minified and " +
            "lock files are skipped automatically; `include`/`exclude` globs narrow that further. Public " +
            "repos need no credentials; a token configured in Settings › Repository digest raises the " +
            "GitHub API rate limit and reaches private repos.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Digest fastapi repo')."),
            "url" to AgentToolParam("string", "GitHub repository, directory or file URL (or 'owner/repo')."),
            "ref" to AgentToolParam("string", "Branch, tag or commit SHA (default: the repo's default branch, or the one in the URL)."),
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
                "repo_digest: expected a github.com repository, /tree/… or /blob/… URL (or owner/repo)",
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
        val token = RepoDigestPrefs.token(context)

        return try {
            val ref = explicitRef.ifBlank { repo.ref.orEmpty() }.ifBlank { defaultBranch(repo, token) }
            val wantedPath = pathArg.ifBlank { repo.path.orEmpty() }
            val wantSingleFile = format == "file" || (wantedPath.isNotEmpty() && isBlobLike(args.optString("url", "")))

            if (wantSingleFile && wantedPath.isNotEmpty()) {
                val content = fetchRaw(repo, ref, wantedPath, token)
                    ?: return ToolExecutionResult(
                        "repo_digest: could not read $wantedPath at $ref (wrong path or private repo without a token?)",
                        false,
                        toolTitle = toolTitle,
                    )
                val text = String(content, Charsets.UTF_8)
                return ToolExecutionResult(
                    buildString {
                        appendLine("Repo: ${repo.slug} @ $ref")
                        appendLine("File: $wantedPath (${content.size} bytes)")
                        appendLine()
                        append(sanitize(text, maxChars))
                    },
                    true,
                    toolTitle = toolTitle,
                )
            }

            val entries = listTree(repo, ref, token)
                ?: return ToolExecutionResult(
                    "repo_digest: GitHub did not return a tree for ${repo.slug} @ $ref " +
                        "(rate limit? configure a token in Settings › Repository digest)",
                    false,
                    toolTitle = toolTitle,
                )

            val matching = entries.filter { entry ->
                entry.type == "blob" &&
                    (wantedPath.isEmpty() || entry.path == wantedPath || entry.path.startsWith("$wantedPath/")) &&
                    matchesGlobs(entry.path, include, exclude) &&
                    !shouldSkip(entry.path)
            }.sortedBy { it.path }

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
                                entry.path to fetchRaw(repo, ref, entry.path, token)
                            }
                        }
                    }.awaitAll().toMap()
                }
            }

            val treeLine = "Repo: ${repo.slug} @ $ref" +
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

    // ── pure helpers (unit-tested) ──────────────────────────────────────────

    /**
     * Parse any of the shapes a user actually pastes: bare `owner/repo`, a repo
     * URL, a `/tree/<ref>/<dir>` link, a `/blob/<ref>/<file>` link, with or
     * without scheme. A branch containing slashes is disambiguated against the
     * known ref unless [knownRefs] is null, in which case the first segment wins —
     * the API call in [defaultBranch]/[listTree] corrects it when it 404s.
     */
    internal fun parseRepoUrl(rawUrl: String, knownRefs: Set<String>? = null): RepoRef? {
        var s = rawUrl.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.removePrefix("www.")
        if (s.startsWith("github.com/")) s = s.removePrefix("github.com/")
        s = s.substringBefore('?').substringBefore('#').trimEnd('/')
        if (s.isEmpty()) return null
        val parts = s.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val owner = parts[0]
        var repo = parts[1].removeSuffix(".git")
        if (owner.isEmpty() || repo.isEmpty()) return null
        var ref: String? = null
        var path: String? = null
        if (parts.size >= 3 && (parts[2] == "tree" || parts[2] == "blob")) {
            val rest = parts.drop(3)
            if (rest.isEmpty()) return RepoRef(owner, repo, null, null)
            if (knownRefs != null) {
                // Longest matching ref wins, so `feature/x` beats `feature`.
                val match = knownRefs.filter { known -> rest.joinToString("/").startsWith(known) }
                    .maxByOrNull { it.length }
                if (match != null) {
                    ref = match
                    path = rest.joinToString("/").removePrefix(match).trimStart('/').ifEmpty { null }
                    return RepoRef(owner, repo, ref, path)
                }
            }
            ref = rest.first()
            path = rest.drop(1).joinToString("/").ifEmpty { null }
        } else if (parts.size > 2) {
            // Not a tree/blob link: treat the tail as a path on the default branch.
            path = parts.drop(2).joinToString("/")
        }
        return RepoRef(owner, repo, ref, path)
    }

    internal fun isBlobLike(rawUrl: String): Boolean = rawUrl.contains("/blob/")

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

    // ── network ─────────────────────────────────────────────────────────────

    /** One blob from the GitHub tree. Internal so the rendering tests can build one. */
    internal data class TreeEntry(val path: String, val type: String, val size: Int)

    private fun request(url: String, token: String, accept: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenMinis/repo_digest")
            .header("Accept", accept)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun defaultBranch(repo: RepoRef, token: String): String {
        val body = get("$API_ROOT/repos/${repo.slug}", token, "application/vnd.github+json") ?: return "HEAD"
        return try {
            JSONObject(body).optString("default_branch", "HEAD").ifBlank { "HEAD" }
        } catch (_: Exception) {
            "HEAD"
        }
    }

    private fun listTree(repo: RepoRef, ref: String, token: String): List<TreeEntry>? {
        val body = get(
            "$API_ROOT/repos/${repo.slug}/git/trees/${encodePath(ref)}?recursive=1",
            token,
            "application/vnd.github+json",
        ) ?: return null
        return try {
            val root = JSONObject(body)
            val array = root.optJSONArray("tree") ?: return null
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val path = item.optString("path")
                    if (path.isEmpty()) continue
                    add(TreeEntry(path, item.optString("type"), item.optInt("size", 0)))
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchRaw(repo: RepoRef, ref: String, path: String, token: String): ByteArray? {
        val url = "$RAW_ROOT/${repo.owner}/${repo.repo}/${encodePath(ref)}/${encodePath(path)}"
        // The raw host is allowlisted, but the guard still runs: a redirect could
        // point anywhere, and the DNS check is free.
        FetchUrlGuard.blockedReason(url)?.let { return null }
        return try {
            client.newCall(request(url, token, "text/plain").get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                body.source().readByteArray(minOf(body.contentLength().coerceAtLeast(0), MAX_FILE_BYTES.toLong()))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun get(url: String, token: String, accept: String): String? {
        FetchUrlGuard.blockedReason(url)?.let { return null }
        return try {
            client.newCall(request(url, token, accept).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Path segments are percent-encoded but slashes stay: refs and paths contain them. */
    internal fun encodePath(raw: String): String =
        raw.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
}
