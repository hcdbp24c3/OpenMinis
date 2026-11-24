package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * [T-android-web-fetch] Fetch a URL and return its content, formatted for a model
 * to read — the tool that replaces "curl through the terminal" and "open it in the
 * browser and copy the text".
 *
 * Ported from tall-1997/OpenMinis-Linux (GPL-3, same licence family as this repo)
 * where it is a plain GET + regex text extraction, then extended to what the two
 * manual routes were actually being used for:
 *
 *  - **headers** — API calls, auth tokens, `Accept-Language`, a site-specific
 *    `Referer`. Parsed from a JSON object or from `Key: value` lines.
 *  - **method / body** — POST (and HEAD) with an optional content type, so a
 *    documented JSON endpoint can be queried directly instead of via curl.
 *  - **JavaScript** — `render: true` drives the real browser engine (the same one
 *    `browser_use` uses) so a client-rendered page yields its actual content, with
 *    a settle delay; when the engine is unavailable the call still returns the
 *    plain HTML rather than failing.
 *  - **format** — `text` (readable), `markdown` (structure kept, links absolute),
 *    `html` (raw) or `json` (pretty-printed).
 *
 * [T-web-m5] additions, all optional so an existing call behaves as before:
 *
 *  - **main-content Markdown** — `format: markdown` goes through [HtmlMarkdown]:
 *    navigation, sidebars, cookie banners and footers are dropped and tables,
 *    nested lists and code languages survive. `extract: full` keeps the whole
 *    page; `extract: main` applies the same detection to `format: text`.
 *  - **paging** — `offset` continues a long page where the previous window
 *    ended ([WebPaging]); the converted page is kept in a small in-memory
 *    cache ([WebCache]) so the next window does not download it again.
 *  - **retry** — GET/HEAD are retried (bounded) on 429/502/503/504
 *    ([HttpRetryPolicy]).
 *  - **clear refusals** — binary content is refused with its type and size
 *    instead of being decoded into noise; guard blocks, timeouts and DNS
 *    failures say which one happened.
 *
 * Everything is bounded: SSRF via [FetchUrlGuard] (the URL, the address every
 * hop resolves to, and every redirect hop before its request is written),
 * response size, and an explicit output window, because a model that cannot
 * see the cut will quote it as if it were the whole page.
 */
object WebFetchTool {
    const val NAME = "web_fetch"
    private const val DEFAULT_MAX_CHARS = 8_000
    private const val HARD_MAX_CHARS = 50_000

    /** Absolute ceiling before extraction: what we are willing to hold in memory. */
    private const val MAX_BODY_BYTES = 2_000_000

    private const val CACHE_TTL_MS = 10L * 60L * 1000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // [T-android-web-fetch] The DNS guard, not just the URL check: it sees
            // the address actually connected to, and runs again for each redirect.
            .dns(FetchUrlGuard.publicInternetDns())
            // [T-web-m5] ...and the URL check again for EVERY hop, before that
            // hop's request is written. OkHttp does not consult a custom Dns for
            // an IP-literal host, so a public page that 302s to
            // `http://169.254.169.254/` was only caught after the fact.
            .addNetworkInterceptor(FetchUrlGuard.hopGuard())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Converted pages, so `offset` windows and `fetch_top` excerpts share one download. */
    private val pageCache: WebCache<LoadedPage> = WebCache(
        maxEntries = 12,
        maxWeight = 3_000_000L,
        ttlMs = CACHE_TTL_MS,
        sizeOf = { it.content.length.toLong() },
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch a public http(s) URL and return its content as readable text " +
            "(or markdown, raw HTML, or pretty JSON). Use web_search first to find URLs, then this " +
            "instead of shelling out to curl or opening the page in the browser. Supports custom " +
            "request headers, POST bodies, and `render: true` to run the page's JavaScript in the " +
            "real browser engine — use that only when the plain fetch returns an app shell or empty " +
            "content, since it is much slower. Private/loopback/metadata hosts are blocked. " +
            "For articles and docs prefer `format: markdown`: it keeps only the main content " +
            "(no navigation, sidebars or footers) with headings, lists, links, code and tables. " +
            "Long pages come in windows: when the output ends with `offset=N`, call again with " +
            "the same url and that offset to read on.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Fetch FastAPI changelog'). Use the same language as the user.",
            ),
            "url" to AgentToolParam("string", "Absolute http or https URL."),
            "method" to AgentToolParam(
                "string",
                "HTTP method (default GET). Use POST with `body` for documented JSON endpoints, or HEAD to check existence/headers cheaply.",
                enumValues = listOf("GET", "POST", "HEAD"),
            ),
            "headers" to AgentToolParam(
                "string",
                "Extra request headers, as a JSON object ({\"X-Api-Key\":\"…\"}) or as `Name: value` lines. Use this for auth tokens, Accept, Accept-Language, Referer.",
            ),
            "body" to AgentToolParam("string", "Request body for POST."),
            "content_type" to AgentToolParam("string", "Content-Type for `body` (default application/json)."),
            "format" to AgentToolParam(
                "string",
                "How to return the content: text (default, readable), markdown (main content with headings/links/lists/code/tables), html (raw), json (pretty-printed when it parses).",
                enumValues = listOf("text", "markdown", "html", "json"),
            ),
            "extract" to AgentToolParam(
                "string",
                "HTML pages only. main = keep the main content and drop navigation/sidebars/footers; full = the whole page. Default (auto): main for format=markdown, full for format=text.",
                enumValues = listOf("auto", "main", "full"),
            ),
            "max_chars" to AgentToolParam(
                "integer",
                "Maximum characters to return (default 8000, maximum 50000). The output says when it was cut.",
            ),
            "offset" to AgentToolParam(
                "integer",
                "Character offset to start from (default 0). Use the offset=N printed at the end of a previous window, with the same url, format and extract, to continue a long page.",
            ),
            "render" to AgentToolParam(
                "boolean",
                "Run the page in the browser engine so its JavaScript executes before extraction. Slower (a real page load plus `wait_ms`), and it loads the page from your device, so use it for client-rendered sites only.",
            ),
            "wait_ms" to AgentToolParam("integer", "render only: how long to let the page settle after load (default 1500)."),
            "timeout_seconds" to AgentToolParam("integer", "Request timeout in seconds (default 45, maximum 120)."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url", "method", "headers", "body", "format", "render"),
    )

    /** One fetched and converted page: what a window is cut from. */
    internal data class LoadedPage(
        val finalUrl: String,
        val status: Int,
        val statusMessage: String,
        val contentType: String,
        val byteCount: Int,
        val capped: Boolean,
        /** The full converted content, before any window is applied. */
        val content: String,
        /** Human-readable note about extraction, or empty. */
        val note: String,
    )

    internal sealed class LoadResult {
        class Ok(val page: LoadedPage) : LoadResult()
        class Fail(val message: String) : LoadResult()
    }

    /**
     * @param render supplied by the caller when the browser engine is available:
     *   `(url, waitMs, wantHtml) -> rendered content`, or null when it is not.
     */
    suspend fun execute(
        argsJson: String,
        context: Context? = null,
        render: (suspend (url: String, waitMs: Int, wantHtml: Boolean) -> String?)? = null,
    ): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return ToolExecutionResult("Error: arguments were not a JSON object", false, toolTitle = NAME)
        }
        val toolTitle = args.optString("tool_title", NAME)
        // A fragment never reaches the server; dropping it here lets a link
        // copied with `#section` work instead of being refused by the guard.
        val url = stripFragment(args.optString("url", "").trim())

        FetchUrlGuard.blockedReason(url)?.let {
            return ToolExecutionResult(guardMessage(url, it), false, toolTitle = toolTitle)
        }

        val method = args.optString("method", "GET").uppercase().ifBlank { "GET" }
        val format = args.optString("format", "text").lowercase().ifBlank { "text" }
        val extract = normalizeExtract(args.optString("extract", "auto"))
        val maxChars = args.optInt("max_chars", DEFAULT_MAX_CHARS).coerceIn(200, HARD_MAX_CHARS)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val wantHtml = format == "html"
        val headers = parseHeaders(args.optString("headers", ""))
        val body = args.optString("body", "")
        val contentType = args.optString("content_type", "application/json")
        val timeoutSeconds = args.optInt("timeout_seconds", 45).coerceIn(5, 120)
        val wantRender = args.optBoolean("render", false) && render != null
        val cacheable = method == "GET"
        val cacheKey = cacheKey(url, format, extract, headers, wantRender)

        // [T-web-m5] A continuation (offset > 0) reads the page the previous
        // window was cut from. A first read (offset 0) always goes to the
        // network: a status endpoint polled twice must not answer from memory.
        if (cacheable && offset > 0) {
            val hit = pageCache.get(cacheKey)
            if (hit != null) {
                return pageResult(hit.value, offset, maxChars, toolTitle, cachedAgeMs = hit.ageMs)
            }
        }

        // [T-android-web-fetch] JavaScript first when asked for, but never as a
        // hard dependency: if the engine is missing or the load fails we fall
        // through to the plain request, because a degraded answer beats no answer.
        if (wantRender && render != null) {
            val waitMs = args.optInt("wait_ms", 1500).coerceIn(0, 15_000)
            // Markdown needs the markup too: converting the engine's flat text
            // would lose every heading and link.
            val needMarkup = wantHtml || format == "markdown" || extract == "main"
            val rendered = try {
                render(url, waitMs, needMarkup)
            } catch (e: Exception) {
                null
            }
            if (!rendered.isNullOrBlank()) {
                val looksLikeMarkup = rendered.contains('<') && rendered.contains('>')
                var note = "Rendered with JavaScript (${waitMs}ms settle)."
                val content: String = when {
                    wantHtml || !looksLikeMarkup -> rendered
                    else -> {
                        val converted = convertHtml(rendered, url, format, extract)
                        if (converted.second.isNotEmpty()) note += " " + converted.second
                        converted.first
                    }
                }
                val page = LoadedPage(
                    finalUrl = url,
                    status = 200,
                    statusMessage = "",
                    contentType = "",
                    byteCount = rendered.length,
                    capped = false,
                    content = content.trim(),
                    note = note,
                )
                if (cacheable) pageCache.put(cacheKey, page)
                return pageResult(page, offset, maxChars, toolTitle, cachedAgeMs = null, rendered = true)
            }
        }

        val result = load(url, method, headers, body, contentType, format, extract, timeoutSeconds)
        return when (result) {
            is LoadResult.Fail -> ToolExecutionResult(result.message, false, toolTitle = toolTitle)
            is LoadResult.Ok -> {
                if (cacheable) pageCache.put(cacheKey, result.page)
                pageResult(result.page, offset, maxChars, toolTitle, cachedAgeMs = null)
            }
        }
    }

    private fun pageResult(
        page: LoadedPage,
        offset: Int,
        maxChars: Int,
        toolTitle: String,
        cachedAgeMs: Long?,
        rendered: Boolean = false,
    ): ToolExecutionResult {
        WebPaging.offsetError(offset, page.content.length)?.let {
            return ToolExecutionResult("Error: $it", false, toolTitle = toolTitle)
        }
        val window = WebPaging.window(page.content, offset, maxChars)
        val header = buildString {
            appendLine("URL: ${page.finalUrl}")
            if (!rendered) {
                appendLine("Status: ${page.status} ${page.statusMessage}".trimEnd())
                if (page.contentType.isNotBlank()) appendLine("Content-Type: ${page.contentType}")
                appendLine("Bytes: ${page.byteCount}${if (page.capped) " (capped)" else ""}")
            }
            if (page.note.isNotBlank()) appendLine(page.note)
            if (cachedAgeMs != null) {
                appendLine("Continuing the page fetched ${cachedAgeMs / 1000}s ago (not re-downloaded).")
            }
        }
        return ToolExecutionResult(
            "$header\n${window.content}${WebPaging.trailer(window)}",
            true,
            toolTitle = toolTitle,
        )
    }

    /**
     * [T-web-m5] Excerpt for `web_search`'s `fetch_top`: the first [maxChars] of
     * the page as main-content Markdown, through exactly the same guarded client
     * as a normal fetch. Blocking; returns null on any failure (an excerpt is a
     * bonus, never a reason to fail the search). The converted page is cached
     * under the key a later `web_fetch(url, format=markdown, offset=N)` uses.
     */
    internal fun excerpt(url: String, maxChars: Int, timeoutSeconds: Int): WebPaging.Window? {
        val clean = stripFragment(url.trim())
        if (FetchUrlGuard.blockedReason(clean) != null) return null
        val key = cacheKey(clean, "markdown", "auto", emptyMap(), false)
        val cached = pageCache.get(key)
        val page: LoadedPage = if (cached != null) {
            cached.value
        } else {
            val loaded = try {
                load(clean, "GET", emptyMap(), "", "application/json", "markdown", "auto", timeoutSeconds, retries = false)
            } catch (_: Exception) {
                return null
            }
            if (loaded !is LoadResult.Ok) return null
            pageCache.put(key, loaded.page)
            loaded.page
        }
        if (page.content.isBlank()) return null
        return WebPaging.window(page.content, 0, maxChars)
    }

    /**
     * One guarded request (plus bounded retries) and the conversion to the
     * requested format. Blocking: call it off the main thread.
     */
    internal fun load(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
        bodyContentType: String,
        format: String,
        extract: String,
        timeoutSeconds: Int,
        retries: Boolean = true,
    ): LoadResult {
        val effectiveClient = if (timeoutSeconds == 45) {
            client
        } else {
            client.newBuilder()
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .connectTimeout(minOf(15L, timeoutSeconds.toLong()), TimeUnit.SECONDS)
                .build()
        }

        val request: Request = try {
            val builder = Request.Builder().url(url)
            val userAgent = com.openminis.app.browser.UserAgentProfile.MOBILE_CHROME.userAgentString
                ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            builder.header("User-Agent", userAgent)
            for ((name, value) in headers) {
                // A caller-supplied UA wins; every other header is additive.
                builder.header(name, value)
            }
            if (!headers.keys.any { it.equals("Accept", ignoreCase = true) }) {
                builder.header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            }
            if (!headers.keys.any { it.equals("Accept-Language", ignoreCase = true) }) {
                builder.header("Accept-Language", "en,zh-CN;q=0.8")
            }
            when (method) {
                "POST" -> builder.post(body.toRequestBody(bodyContentType.toMediaTypeOrNull()))
                "HEAD" -> builder.head()
                else -> builder.get()
            }
            builder.build()
        } catch (e: Exception) {
            return LoadResult.Fail("Error fetching $url: ${e.message ?: e.javaClass.simpleName}")
        }

        var attempt = 0
        while (true) {
            val outcome: LoadResult? = try {
                effectiveClient.newCall(request).execute().use { response ->
                    val finalUrl = stripFragment(response.request.url.toString())
                    val finalPort = response.request.url.port
                    // [T-android-web-fetch] A redirect is a NEW URL, and the model
                    // never saw it: re-check the destination explicitly, even though
                    // the DNS guard and the hop guard already vetted it.
                    val blocked = FetchUrlGuard.blockedReason(finalUrl)
                    if (blocked != null) {
                        return@use LoadResult.Fail("Error after redirect: " + guardMessage(finalUrl, blocked).removePrefix("Error: "))
                    }
                    if (finalPort != 80 && finalPort != 443) {
                        return@use LoadResult.Fail("Error: refusing to read a non-standard port ($finalPort) — $finalUrl")
                    }

                    val contentTypeHeader = response.header("Content-Type").orEmpty()
                    val code = response.code

                    if (retries && HttpRetryPolicy.isRetryableStatus(code) && HttpRetryPolicy.isRetryableMethod(method)) {
                        val wait = HttpRetryPolicy.delayMs(attempt + 1, response.header("Retry-After"))
                        if (wait != null) {
                            attempt += 1
                            try {
                                Thread.sleep(wait)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@use LoadResult.Fail("Error fetching $url: interrupted")
                            }
                            return@use null
                        }
                    }

                    // Refuse binary before draining it: 2MB of PDF decoded as
                    // UTF-8 is noise the model would try to read.
                    if (response.isSuccessful && method != "HEAD" && isBinaryContentType(contentTypeHeader)) {
                        val declared = response.header("Content-Length")?.trim()?.toLongOrNull()
                        return@use LoadResult.Fail(binaryMessage(finalUrl, contentTypeHeader, declared))
                    }

                    // [T-http-body-read] Bounded drain, never an exact-count read: a
                    // compressed or chunked body reports contentLength() == -1, and
                    // `readByteArray(n)` then demanded the whole ceiling and threw
                    // EOFException on every gzip'd page.
                    val bytes = HttpBodyReader.readCapped(response.body, MAX_BODY_BYTES)

                    if (response.isSuccessful && method != "HEAD" && looksBinary(bytes)) {
                        return@use LoadResult.Fail(
                            binaryMessage(finalUrl, contentTypeHeader.ifBlank { "binary data" }, bytes.size.toLong()),
                        )
                    }
                    val raw = String(bytes, detectCharset(contentTypeHeader, bytes))

                    if (!response.isSuccessful) {
                        // Include the body: an API error explains itself there, and the
                        // model can act on it instead of retrying blindly.
                        val detail = truncateWithNote(raw.trim(), 1200)
                        val retried = if (attempt > 0) " (after $attempt ${if (attempt == 1) "retry" else "retries"})" else ""
                        val hint = HttpRetryPolicy.describe(code, response.header("Retry-After"))
                        return@use LoadResult.Fail(
                            "HTTP $code ${response.message} for $finalUrl$retried — $hint\n$contentTypeHeader\n\n$detail",
                        )
                    }

                    val isJson = contentTypeHeader.contains("json", ignoreCase = true) ||
                        raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[")
                    val isHtml = contentTypeHeader.contains("html", ignoreCase = true) ||
                        (contentTypeHeader.isBlank() && looksLikeHtml(raw))

                    var note = ""
                    val content: String = when (format) {
                        "html" -> raw
                        "json" -> prettyJsonIfPossible(raw) ?: raw
                        else -> when {
                            isHtml -> {
                                val converted = convertHtml(raw, finalUrl, format, extract)
                                note = converted.second
                                converted.first
                            }
                            format == "markdown" -> raw
                            isJson -> prettyJsonIfPossible(raw) ?: raw
                            else -> raw
                        }
                    }

                    LoadResult.Ok(
                        LoadedPage(
                            finalUrl = finalUrl,
                            status = code,
                            statusMessage = response.message,
                            contentType = contentTypeHeader,
                            byteCount = bytes.size,
                            capped = bytes.size >= MAX_BODY_BYTES,
                            content = content.trim(),
                            note = note,
                        ),
                    )
                }
            } catch (e: Exception) {
                LoadResult.Fail(describeFailure(url, e, timeoutSeconds))
            }
            if (outcome != null) return outcome
        }
    }

    /**
     * HTML to the requested readable format. Returns the content and a one-line
     * note about what was kept (empty for the unchanged legacy text path).
     */
    internal fun convertHtml(raw: String, baseUrl: String?, format: String, extract: String): Pair<String, String> {
        val markdown = format == "markdown"
        // auto: main-content Markdown, whole-page text — text stays what it was.
        val mainOnly = extract == "main" || (extract == "auto" && markdown)
        if (!markdown && !mainOnly) return Pair(htmlToText(raw), "")
        return try {
            val page = HtmlMarkdown.convert(raw, baseUrl, mainOnly = mainOnly, markdown = markdown)
            val note = when {
                !mainOnly -> ""
                page.mode == "full" -> "Extraction: no main content detected, whole page returned."
                else -> "Extraction: main content (${page.mode}); pass extract=full for the whole page."
            }
            if (page.content.isBlank()) {
                Pair(if (markdown) htmlToMarkdown(raw, baseUrl) else htmlToText(raw), "")
            } else {
                Pair(page.content, note)
            }
        } catch (_: Exception) {
            // The converter must never be the reason a page cannot be read.
            Pair(if (markdown) htmlToMarkdown(raw, baseUrl) else htmlToText(raw), "")
        } catch (_: StackOverflowError) {
            Pair(if (markdown) htmlToMarkdown(raw, baseUrl) else htmlToText(raw), "")
        }
    }

    // ── pure helpers (unit-tested) ──────────────────────────────────────────

    internal fun normalizeExtract(raw: String): String = when (raw.trim().lowercase()) {
        "main", "article", "readable" -> "main"
        "full", "all", "page" -> "full"
        else -> "auto"
    }

    /** The URL without its `#fragment`; everything else untouched. */
    internal fun stripFragment(url: String): String {
        val hash = url.indexOf('#')
        return if (hash >= 0) url.substring(0, hash) else url
    }

    /**
     * Cache identity of a converted page: everything that changes what the
     * windows are cut from. Header VALUES go in by hash, so a token is never
     * held as a map key.
     */
    internal fun cacheKey(
        url: String,
        format: String,
        extract: String,
        headers: Map<String, String>,
        rendered: Boolean,
    ): String {
        val headerPart = if (headers.isEmpty()) {
            "-"
        } else {
            headers.entries
                .map { it.key.lowercase() + ":" + it.value }
                .sorted()
                .joinToString("\n")
                .hashCode()
                .toString(16)
        }
        return listOf(if (rendered) "render" else "plain", format, extract, headerPart, url).joinToString("|")
    }

    internal fun guardMessage(url: String, reason: String): String =
        "Error: blocked by the URL guard — $reason. web_fetch only reads public http(s) pages; " +
            "local, private-network and cloud-metadata addresses are refused. " +
            "For a service inside the sandbox use shell_execute (curl) instead."

    private val BINARY_TYPE = Regex(
        "(?i)^\\s*(application/(pdf|zip|gzip|x-gzip|x-tar|x-7z-compressed|x-rar-compressed|" +
            "x-bzip2|x-xz|msword|vnd\\.[a-z0-9.+-]+|x-protobuf|protobuf|wasm|java-archive|" +
            "x-apple-diskimage|x-msdownload|epub\\+zip|x-sqlite3)|image/|audio/|video/|font/)",
    )

    /** Declared types that are never text. `image/svg+xml` and `*+json`/`*+xml` vendor types are text. */
    internal fun isBinaryContentType(header: String): Boolean {
        val type = header.substringBefore(';').trim().lowercase()
        if (type.isEmpty()) return false
        if (type == "image/svg+xml" || type.endsWith("+json") || type.endsWith("+xml")) return false
        return BINARY_TYPE.containsMatchIn(type)
    }

    /** A NUL in the first kilobyte: no text encoding we decode produces one. */
    internal fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 1024)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    internal fun binaryMessage(url: String, contentType: String, size: Long?): String {
        val sizePart = if (size != null && size >= 0) "${size} bytes" else "size unknown"
        return "Error: $url is ${contentType.substringBefore(';').trim()} ($sizePart), not text — web_fetch only returns text. " +
            "Download it in the sandbox with shell_execute (curl -L -o <file> '<url>') and inspect it there " +
            "(for an image use read_image; for a PDF extract its text with a tool such as pdftotext)."
    }

    internal fun looksLikeHtml(raw: String): Boolean {
        val head = raw.take(512).trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<head") || head.contains("<body")
    }

    /**
     * Charset from the `Content-Type` header, else from a `<meta charset>` in
     * the first 2KB, else UTF-8. An unknown name is UTF-8 too: a wrong label
     * must not turn into a thrown exception.
     */
    internal fun detectCharset(contentTypeHeader: String, bytes: ByteArray): Charset {
        val fromHeader = Regex("(?i)charset\\s*=\\s*\"?([a-z0-9._:-]+)").find(contentTypeHeader)?.groupValues?.get(1)
        val name = fromHeader ?: run {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            Regex("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-z0-9._:-]+)").find(head)?.groupValues?.get(1)
        }
        if (name.isNullOrBlank()) return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    /** Which of the things that can go wrong went wrong, in words the model can act on. */
    internal fun describeFailure(url: String, e: Throwable, timeoutSeconds: Int): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Blocked request hop") || message.contains("Blocked non-public DNS answer") ->
                "Error fetching $url: blocked by the URL guard — $message. " +
                    "The address (or a redirect from it) points at a local, private or metadata host."
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException ->
                "Error fetching $url: timed out after ${timeoutSeconds}s. The site may be slow or unreachable from this network; " +
                    "retry with a larger timeout_seconds, or try another source."
            e is java.net.UnknownHostException ->
                "Error fetching $url: host not found (DNS) — check the spelling of the domain."
            e is javax.net.ssl.SSLException ->
                "Error fetching $url: TLS failure — ${message.ifBlank { e.javaClass.simpleName }}"
            e is java.net.ConnectException ->
                "Error fetching $url: connection refused or unreachable — ${message.ifBlank { e.javaClass.simpleName }}"
            else -> "Error fetching $url: ${message.ifBlank { e.javaClass.simpleName }}"
        }
    }

    /**
     * Headers, from a JSON object or from `Name: value` lines.
     *
     * Both spellings exist because the model writes whichever comes naturally for
     * the call at hand, and rejecting one of them produces a tool call that looks
     * right and silently drops the credential. Blank names, names containing a
     * space or newline (header-splitting), and hop-by-hop headers the client owns
     * are skipped.
     */
    internal fun parseHeaders(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        fun put(name: String, value: String) {
            val key = name.trim()
            if (key.isEmpty() || key.any { it == '\n' || it == '\r' || it == ' ' || it == ':' }) return
            if (IGNORED_HEADERS.contains(key.lowercase())) return
            out[key] = value.trim()
        }
        if (trimmed.startsWith("{")) {
            val obj = try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                return emptyMap()
            }
            for (key in obj.keys()) put(key, obj.optString(key, ""))
            return out
        }
        for (line in trimmed.split('\n', '\r')) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            put(line.substring(0, idx), line.substring(idx + 1))
        }
        return out
    }

    /** Headers the HTTP client owns; letting the model set them breaks the request. */
    private val IGNORED_HEADERS = setOf(
        "host", "content-length", "connection", "transfer-encoding",
        "expect", "upgrade", "te", "trailer", "proxy-connection",
    )

    /**
     * Pretty-print when the body is JSON; null when it is not.
     *
     * Own printer rather than `JSONObject.toString(2)`, which only indents nested
     * ARRAYS: measured against org.json 20231013 (this module's test classpath),
     * `{"a":{"b":1}}` came back as a single line while `[1,2]` was already
     * indented. A model reading `format=json` should get the shape either way, and
     * the iOS twin always line-breaks (JSONSerialization's `.prettyPrinted`).
     *
     * Key order follows whatever the payload's parser produced; org.json does not
     * promise source order, so this does not either.
     */
    internal fun prettyJsonIfPossible(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val out = StringBuilder()
            when (trimmed.first()) {
                '{' -> writeJson(JSONObject(trimmed), out, 0)
                '[' -> writeJson(JSONArray(trimmed), out, 0)
                else -> return null
            }
            out.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeJson(value: Any?, out: StringBuilder, indent: Int) {
        val pad = "  ".repeat(indent)
        val childPad = "  ".repeat(indent + 1)
        when (value) {
            is JSONObject -> {
                if (value.length() == 0) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val keys = value.keys().asSequence().toList()
                keys.forEachIndexed { index, key ->
                    out.append(childPad).append(JSONObject.quote(key)).append(": ")
                    writeJson(value.opt(key), out, indent + 1)
                    if (index != keys.lastIndex) out.append(',')
                    out.append('\n')
                }
                out.append(pad).append('}')
            }
            is JSONArray -> {
                if (value.length() == 0) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                for (index in 0 until value.length()) {
                    out.append(childPad)
                    writeJson(value.opt(index), out, indent + 1)
                    if (index != value.length() - 1) out.append(',')
                    out.append('\n')
                }
                out.append(pad).append(']')
            }
            is String -> out.append(JSONObject.quote(value))
            null -> out.append("null")
            else -> out.append(value.toString())
        }
    }

    /**
     * Readable text: scripts/styles dropped, block tags turned into line breaks so
     * paragraphs and list items do not run together, entities decoded, whitespace
     * collapsed per line. Adapted from the OpenMinis-Linux extractor (GPL-3).
     */
    internal fun htmlToText(raw: String): String {
        var s = raw
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<noscript[^>]*>.*?</noscript>").replace(s, " ")
        s = Regex("(?is)<head[^>]*>.*?</head>").replace(s, " ")
        // Block boundaries become newlines BEFORE tags are stripped, otherwise
        // every paragraph boundary is lost and the whole page reads as one line.
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|section|article|li|tr|h[1-6]|blockquote|pre)>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "\n- ")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = HtmlText.decodeEntities(s)
        return s.split('\n')
            .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Markdown: the same text with the structure a model can still see — headings,
     * list items, links (absolute, resolved against [baseUrl]), fenced code blocks
     * and inline emphasis.
     *
     * Deliberately not a full HTML→Markdown engine: tables, images and nested
     * lists degrade to text. The value here is that links survive (the model can
     * fetch them next) without shipping a converter dependency.
     */
    internal fun htmlToMarkdown(raw: String, baseUrl: String? = null): String {
        var s = raw
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<noscript[^>]*>.*?</noscript>").replace(s, " ")
        s = Regex("(?is)<head[^>]*>.*?</head>").replace(s, " ")
        s = Regex("(?is)<pre[^>]*>(.*?)</pre>").replace(s) { m ->
            "\n```\n" + HtmlText.decodeEntities(Regex("(?is)<[^>]+>").replace(m.groupValues[1], "")) + "\n```\n"
        }
        for (level in 1..6) {
            s = s.replace(Regex("(?is)<h$level[^>]*>(.*?)</h$level>")) { m ->
                "\n" + "#".repeat(level) + " " + collapseSpaces(m.groupValues[1]) + "\n"
            }
        }
        s = s.replace(Regex("(?is)<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")) { m ->
            val text = collapseSpaces(m.groupValues[2])
            val href = absoluteUrl(m.groupValues[1], baseUrl)
            if (text.isBlank()) href else if (href.isBlank()) text else "[$text]($href)"
        }
        s = s.replace(Regex("(?is)</?(strong|b)(\\s[^>]*)?>"), "**")
        s = s.replace(Regex("(?is)</?(em|i)(\\s[^>]*)?>"), "*")
        s = s.replace(Regex("(?is)<code[^>]*>(.*?)</code>")) { m -> "`" + collapseSpaces(m.groupValues[1]) + "`" }
        s = s.replace(Regex("(?i)<li[^>]*>"), "\n- ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|section|article|li|tr|h[1-6]|blockquote)>"), "\n")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = HtmlText.decodeEntities(s)
        return s.split('\n')
            .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trimEnd() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Relative links keep their meaning once resolved against the page URL. */
    internal fun absoluteUrl(href: String, baseUrl: String?): String {
        val raw = href.trim()
        if (raw.isEmpty() || baseUrl.isNullOrBlank()) return raw
        if (raw.startsWith("#") || raw.startsWith("data:") || raw.startsWith("javascript:")) return raw
        return try {
            URI(baseUrl).resolve(raw).toString()
        } catch (_: Exception) {
            raw
        }
    }

    /** Says explicitly that content was cut — a silent cut gets quoted as the whole page. */
    internal fun truncateWithNote(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) +
            "\n\n[truncated: showing $maxChars of ${text.length} characters — request a narrower page or a larger max_chars]"
    }

    private fun collapseSpaces(raw: String): String =
        HtmlText.decodeEntities(Regex("(?is)<[^>]+>").replace(raw, " "))
            .replace(Regex("\\s+"), " ")
            .trim()


}
