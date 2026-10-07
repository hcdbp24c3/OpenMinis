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
 * Everything is bounded: SSRF via [FetchUrlGuard] (including redirect hops),
 * response size, and an explicit output cap with a truncation notice, because a
 * model that cannot see the cut will quote it as if it were the whole page.
 */
object WebFetchTool {
    const val NAME = "web_fetch"
    private const val DEFAULT_MAX_CHARS = 8_000
    private const val HARD_MAX_CHARS = 50_000

    /** Absolute ceiling before extraction: what we are willing to hold in memory. */
    private const val MAX_BODY_BYTES = 2_000_000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // [T-android-web-fetch] The DNS guard, not just the URL check: it sees
            // the address actually connected to, and runs again for each redirect.
            .dns(FetchUrlGuard.publicInternetDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch a public http(s) URL and return its content as readable text " +
            "(or markdown, raw HTML, or pretty JSON). Use web_search first to find URLs, then this " +
            "instead of shelling out to curl or opening the page in the browser. Supports custom " +
            "request headers, POST bodies, and `render: true` to run the page's JavaScript in the " +
            "real browser engine — use that only when the plain fetch returns an app shell or empty " +
            "content, since it is much slower. Private/loopback/metadata hosts are blocked.",
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
                "How to return the content: text (default, readable), markdown (keeps headings/links/lists), html (raw), json (pretty-printed when it parses).",
                enumValues = listOf("text", "markdown", "html", "json"),
            ),
            "max_chars" to AgentToolParam(
                "integer",
                "Maximum characters to return (default 8000, maximum 50000). The output says when it was cut.",
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
        val url = args.optString("url", "").trim()

        FetchUrlGuard.blockedReason(url)?.let {
            return ToolExecutionResult("Error: $it", false, toolTitle = toolTitle)
        }

        val method = args.optString("method", "GET").uppercase().ifBlank { "GET" }
        val format = args.optString("format", "text").lowercase().ifBlank { "text" }
        val maxChars = args.optInt("max_chars", DEFAULT_MAX_CHARS).coerceIn(200, HARD_MAX_CHARS)
        val wantHtml = format == "html"
        val headers = parseHeaders(args.optString("headers", ""))
        val body = args.optString("body", "")
        val contentType = args.optString("content_type", "application/json")
        val timeoutSeconds = args.optInt("timeout_seconds", 45).coerceIn(5, 120)

        // [T-android-web-fetch] JavaScript first when asked for, but never as a
        // hard dependency: if the engine is missing or the load fails we fall
        // through to the plain request, because a degraded answer beats no answer.
        if (args.optBoolean("render", false) && render != null) {
            val waitMs = args.optInt("wait_ms", 1500).coerceIn(0, 15_000)
            val rendered = try {
                render(url, waitMs, wantHtml)
            } catch (e: Exception) {
                null
            }
            if (!rendered.isNullOrBlank()) {
                val formatted = when {
                    // HTML and markdown both need the markup; only plain text is
                    // extracted from the RENDERED dom (scripts already executed, so
                    // its text is what the page actually shows).
                    wantHtml || format == "markdown" -> rendered
                    else -> htmlToText(rendered)
                }
                val content = if (format == "markdown") htmlToMarkdown(rendered, url) else formatted
                return ToolExecutionResult(
                    buildString {
                        appendLine("URL: $url")
                        appendLine("Rendered with JavaScript (${waitMs}ms settle).")
                        appendLine()
                        append(truncateWithNote(content.trim(), maxChars))
                    },
                    true,
                    toolTitle = toolTitle,
                )
            }
        }

        val effectiveClient = if (timeoutSeconds == 45) {
            client
        } else {
            client.newBuilder()
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .connectTimeout(minOf(15L, timeoutSeconds.toLong()), TimeUnit.SECONDS)
                .build()
        }

        return try {
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
                "POST" -> builder.post(body.toRequestBody(contentType.toMediaTypeOrNull()))
                "HEAD" -> builder.head()
                else -> builder.get()
            }

            effectiveClient.newCall(builder.build()).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val finalPort = response.request.url.port
                // [T-android-web-fetch] A redirect is a NEW URL, and the model
                // never saw it: re-check the destination explicitly, even though
                // the DNS guard already vetted the address it connected to.
                FetchUrlGuard.blockedReason(finalUrl)?.let {
                    return ToolExecutionResult("Error after redirect: $it", false, toolTitle = toolTitle)
                }
                if (finalPort != 80 && finalPort != 443) {
                    return ToolExecutionResult(
                        "Error: refusing to read a non-standard port ($finalPort) — $finalUrl",
                        false,
                        toolTitle = toolTitle,
                    )
                }

                val contentTypeHeader = response.header("Content-Type").orEmpty()
                // [T-http-body-read] Bounded drain, never an exact-count read: a
                // compressed or chunked body reports contentLength() == -1, and
                // `readByteArray(n)` then demanded the whole ceiling and threw
                // EOFException on every gzip'd page.
                val bytes = HttpBodyReader.readCapped(response.body, MAX_BODY_BYTES)
                val raw = String(bytes, Charsets.UTF_8)

                if (!response.isSuccessful) {
                    // Include the body: an API error explains itself there, and the
                    // model can act on it instead of retrying blindly.
                    val detail = truncateWithNote(raw.trim(), 1200)
                    return ToolExecutionResult(
                        "HTTP ${response.code} ${response.message} for $finalUrl\n$contentTypeHeader\n\n$detail",
                        false,
                        toolTitle = toolTitle,
                    )
                }

                val isJson = contentTypeHeader.contains("json", ignoreCase = true) ||
                    raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[")
                val isHtml = contentTypeHeader.contains("html", ignoreCase = true)

                val content = when (format) {
                    "html" -> raw
                    "json" -> prettyJsonIfPossible(raw) ?: raw
                    "markdown" -> if (isHtml) htmlToMarkdown(raw, finalUrl) else raw
                    else -> when {
                        isHtml -> htmlToText(raw)
                        isJson -> prettyJsonIfPossible(raw) ?: raw
                        else -> raw
                    }
                }

                val header = buildString {
                    appendLine("URL: $finalUrl")
                    appendLine("Status: ${response.code} ${response.message}")
                    if (contentTypeHeader.isNotBlank()) appendLine("Content-Type: $contentTypeHeader")
                    appendLine("Bytes: ${bytes.size}${if (bytes.size >= MAX_BODY_BYTES) " (capped)" else ""}")
                }
                ToolExecutionResult(
                    "$header\n${truncateWithNote(content.trim(), maxChars)}",
                    true,
                    toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                "Error fetching $url: ${e.message ?: e.javaClass.simpleName}",
                false,
                toolTitle = toolTitle,
            )
        }
    }

    // ── pure helpers (unit-tested) ──────────────────────────────────────────

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
