package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * [T-android-web-search] Search the public web and return titles, URLs and
 * snippets, instead of opening a browser for a fact.
 *
 * Ported from tall-1997/OpenMinis-Linux (GPL-3, same licence as this repo — see
 * THIRD_PARTY_LICENSES), then extended to Kelivo's full provider set
 * (`lib/core/services/search/providers/`, 26 services) minus the one that only
 * talks to Kelivo's own backend.
 *
 * Backends live in [WebSearchSettings.Engine]; the no-key ones (DuckDuckGo HTML,
 * Bing's HTML endpoint) come first so the tool works on a fresh install. Keyed
 * backends accept SEVERAL keys and rotate them ([SearchKeyRotator]) — Kelivo's
 * "multiple search" — so a per-key quota is not the ceiling on how much the
 * agent can search. When the chosen engine returns nothing, the no-key HTML
 * fallbacks (Wikipedia, Bing, Mojeek) run before the call is reported failed.
 *
 * Every engine's request shape and parse route mirrors its Kelivo counterpart:
 * a vendor that answers one client and rejects the other is the failure this
 * pairing exists to prevent.
 */
object WebSearchTool {
    const val NAME = "web_search"
    private const val MAX_RESULTS = 8
    private const val TIMEOUT_MS = 15_000

    data class Result(
        val title: String,
        val url: String,
        val snippet: String,
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the public web and return titles, URLs, and snippets. " +
            "Use this for facts, docs, news, and package versions instead of opening a browser. " +
            "Follow up with browser_use only when you need to interact with a specific page. " +
            "Uses Settings → Web search. First-class backends: Tavily, Bocha, Exa, Brave, Jina, Zhipu, Bing, SearXNG. " +
            "Falls back to DuckDuckGo, then suggest browser_use for a specific URL.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Search Android 15 release notes'). Use the same language as the user.",
            ),
            "query" to AgentToolParam("string", "Search query. Be specific; include version numbers or site: filters when useful."),
            "max_results" to AgentToolParam("integer", "How many results to return (default 5, max 8)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "max_results"),
    )

    fun execute(argsJson: String, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val query = args.optString("query", "").trim()
            val toolTitle = args.optString("tool_title", NAME)
            val max = args.optInt("max_results", 5).coerceIn(1, MAX_RESULTS)
            if (query.isEmpty()) {
                return ToolExecutionResult("query is required", success = false, toolTitle = toolTitle)
            }
            val preferred = context?.let { WebSearchSettings.engine(it) } ?: WebSearchSettings.Engine.DDG
            val allowFallback = context?.let { WebSearchSettings.fallbackEnabled(it) } ?: true
            val engines = mutableListOf(preferred)
            // A user who picked a no-key engine must not silently spend keyed
            // quotas when it returns nothing. Keyed fallback only runs after a
            // KEYED engine was the one they asked for.
            if (allowFallback && context != null && preferred.needsKey) {
                for (keyed in WebSearchSettings.configuredKeyed(context)) {
                    if (keyed != preferred) engines += keyed
                }
                if (WebSearchSettings.Engine.DDG !in engines) engines += WebSearchSettings.Engine.DDG
            }
            var lastError: String? = null
            var used = preferred
            var results: List<Result> = emptyList()
            for (engine in engines) {
                used = engine
                val attempt = search(engine, query, max, context)
                if (attempt.results.isNotEmpty()) {
                    results = attempt.results
                    lastError = null
                    break
                }
                lastError = attempt.error
            }
            if (results.isEmpty()) {
                val fallback = searchNoKeyFallback(query, max, context)
                if (fallback.isNotEmpty()) {
                    return ToolExecutionResult(
                        format(query, fallback, "no-key-fallback"),
                        success = true,
                        toolTitle = toolTitle,
                    )
                }
                return ToolExecutionResult(
                    "web_search failed for \"$query\" via ${preferred.id}: ${lastError ?: "no results"}. " +
                        "DuckDuckGo HTML returned no cards, and the no-key Wikipedia/Bing/Mojeek fallback was also empty. " +
                        "Configure Settings → Web search, or open a known URL with browser_use.",
                    success = false,
                    toolTitle = toolTitle,
                )
            }
            ToolExecutionResult(format(query, results, used.id), success = true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("web_search failed: ${e.message}", success = false)
        }
    }

    private data class Attempt(val results: List<Result>, val error: String?)

    private fun search(
        engine: WebSearchSettings.Engine,
        query: String,
        max: Int,
        context: Context?,
    ): Attempt {
        return try {
            when (engine) {
                WebSearchSettings.Engine.DDG -> searchDuckDuckGo(query, max, context)

                // [T-android-web-search] Bing's public HTML endpoint: no key, and
                // the reason "Bing is free" — the paid Web Search API is a
                // separate engine (BING) for users who have a subscription key.
                WebSearchSettings.Engine.BING_LOCAL -> {
                    if (max <= 0) return Attempt(emptyList(), null)
                    val html = fetchUrl(
                        "https://www.bing.com/search?q=${enc(query)}&setlang=zh-Hans",
                        context = context,
                    ) ?: return Attempt(emptyList(), "empty response from Bing HTML")
                    val parsed = parseBingHtml(html, max)
                    Attempt(parsed, if (parsed.isEmpty()) "Bing HTML returned no results" else null)
                }

                WebSearchSettings.Engine.SEARXNG -> {
                    val base = context?.let { WebSearchSettings.searxngUrl(it) }.orEmpty().trimEnd('/')
                    if (base.isEmpty()) return Attempt(emptyList(), "SearXNG URL is not configured")
                    val endpoint = if (base.endsWith("/search")) base else "$base/search"
                    val headers = linkedMapOf<String, String>()
                    // [T-android-web-search] Kelivo's SearXNG supports an instance
                    // behind HTTP Basic auth; blank means anonymous.
                    val auth = context?.let { WebSearchSettings.searxngAuth(it) }.orEmpty()
                    if (auth.isNotEmpty()) {
                        val token = android.util.Base64.encodeToString(
                            auth.toByteArray(StandardCharsets.UTF_8),
                            android.util.Base64.NO_WRAP,
                        )
                        headers["Authorization"] = "Basic $token"
                    }
                    val body = fetchUrl("$endpoint?q=${enc(query)}&format=json", headers, context)
                        ?: return Attempt(emptyList(), "empty response from SearXNG")
                    val parsed = parseSearxJson(body, max)
                    Attempt(parsed, if (parsed.isEmpty()) "SearXNG returned no results" else null)
                }

                WebSearchSettings.Engine.CUSTOM -> {
                    val template = context?.let { WebSearchSettings.customUrl(it) }.orEmpty()
                    if (template.isEmpty()) return Attempt(emptyList(), "Custom search URL is not configured")
                    val key = context?.let { WebSearchSettings.customKey(it) }.orEmpty()
                    val headerName = context?.let { WebSearchSettings.customKeyHeader(it) }.orEmpty()
                    val endpoint = expandCustomUrl(template, query, key)
                    val headers = linkedMapOf("Accept" to "application/json, text/html")
                    if (key.isNotEmpty() && !template.contains("{key}", ignoreCase = true)) {
                        val h = headerName.ifBlank { "Authorization" }
                        val v = if (h.equals("Authorization", ignoreCase = true) &&
                            !key.startsWith("Bearer ", ignoreCase = true)
                        ) {
                            "Bearer $key"
                        } else {
                            key
                        }
                        headers[h] = v
                    }
                    val body = fetchUrl(endpoint, headers, context)
                        ?: return Attempt(emptyList(), "empty response from custom search")
                    val trimmed = body.trimStart()
                    val parsed = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        parseGenericSearchJson(body, max)
                    } else {
                        parseHtml(body, max)
                    }
                    Attempt(parsed, if (parsed.isEmpty()) "Custom search returned no results" else null)
                }

                WebSearchSettings.Engine.TAVILY -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("query", query)
                        .put("max_results", max)
                        .put("search_depth", "basic"),
                    keyField = "api_key",
                    max = max,
                )

                WebSearchSettings.Engine.BING -> keyedGet(
                    engine, context,
                    url = "${engine.resolvedUrl(context)}?q=${enc(query)}&count=$max",
                    headerName = "Ocp-Apim-Subscription-Key",
                    max = max,
                    parse = ::parseBingJson,
                )

                WebSearchSettings.Engine.BRAVE -> keyedGet(
                    engine, context,
                    url = "${engine.resolvedUrl(context)}?q=${enc(query)}&count=$max",
                    headerName = "X-Subscription-Token",
                    max = max,
                    parse = { json, n -> parseGenericSearchJson(json, n) },
                )

                WebSearchSettings.Engine.EXA -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("query", query)
                        .put("numResults", max)
                        .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 400))),
                    headerName = "x-api-key",
                    max = max,
                )

                WebSearchSettings.Engine.JINA -> keyedGet(
                    engine, context,
                    url = if (engine.urlOverride(context).isNotBlank()) {
                        val base = engine.urlOverride(context).trimEnd('/')
                        "$base/${enc(query)}"
                    } else {
                        "https://s.jina.ai/${enc(query)}"
                    },
                    headerName = "Authorization",
                    bearer = true,
                    extra = mapOf("Accept" to "application/json"),
                    max = max,
                    parse = { json, n -> parseGenericSearchJson(json, n) },
                )

                WebSearchSettings.Engine.BOCHA -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("query", query).put("count", max).put("summary", true),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.ZHIPU -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("search_query", query).put("count", max),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.KAGI -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("query", query)
                        .put("workflow", "search")
                        .put("format", "json")
                        .put("limit", max),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.LINKUP -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("q", query)
                        .put("depth", "standard")
                        .put("outputType", "sourcedAnswer")
                        .put("includeImages", "false"),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.METASO -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("q", query)
                        .put("scope", "webpage")
                        .put("size", max)
                        .put("includeSummary", false),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.OLLAMA -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("query", query).put("max_results", max.coerceAtMost(10)),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.PARALLEL -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("objective", query)
                        .put("search_queries", JSONArray().put(query))
                        .put("mode", "basic"),
                    headerName = "x-api-key",
                    max = max,
                )

                WebSearchSettings.Engine.PERPLEXITY -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("query", query).put("max_results", max.coerceAtMost(20)),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.QUERIT -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("query", query).put("count", max),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.SERPER -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("q", query).put("num", max),
                    headerName = "X-API-KEY",
                    max = max,
                )

                WebSearchSettings.Engine.STEPFUN -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("query", query),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.TINYFISH -> keyedGet(
                    engine, context,
                    url = "${engine.resolvedUrl(context).trimEnd('/')}?query=${enc(query)}&num=$max",
                    headerName = "X-API-Key",
                    max = max,
                    parse = { json, n -> parseGenericSearchJson(json, n) },
                )

                WebSearchSettings.Engine.YOU -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject().put("query", query).put("count", max),
                    headerName = "X-API-Key",
                    max = max,
                )

                WebSearchSettings.Engine.FIRECRAWL -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("query", query)
                        .put("limit", max.coerceIn(1, 100))
                        .put("sources", JSONArray().put("web").put("news")),
                    bearer = true,
                    max = max,
                )

                WebSearchSettings.Engine.ANYSEARCH -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("query", query)
                        .put("max_results", max.coerceAtMost(20))
                        .put("format", "json"),
                    bearer = true,
                    max = max,
                )

                // Vendor-specific shapes: Doubao's PascalCase envelope, Kimi's
                // tools endpoints with chunked text, Grok's Responses-API answer
                // plus url_citation annotations.
                WebSearchSettings.Engine.DOUBAO -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("Query", query)
                        .put("SearchType", "web")
                        .put("Count", max)
                        .put("Filter", JSONObject().put("NeedUrl", true)),
                    bearer = true,
                    max = max,
                    parse = ::parseDoubaoJson,
                )

                WebSearchSettings.Engine.KIMI -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("text_query", query)
                        .put("limit", max.coerceAtMost(20))
                        .put("timeout_seconds", 30),
                    bearer = true,
                    max = max,
                    parse = ::parseKimiJson,
                )

                WebSearchSettings.Engine.GROK -> keyedPost(
                    engine, context,
                    url = engine.resolvedUrl(context),
                    body = JSONObject()
                        .put("model", "grok-4.5")
                        .put(
                            "input",
                            JSONArray()
                                .put(JSONObject().put("role", "system").put("content", GROK_SYSTEM_PROMPT))
                                .put(JSONObject().put("role", "user").put("content", query)),
                        )
                        .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                        .put("store", false)
                        .put("stream", false)
                        .put("reasoning", JSONObject().put("effort", "low")),
                    bearer = true,
                    max = max,
                    parse = ::parseGrokJson,
                )
            }
        } catch (e: Exception) {
            Attempt(emptyList(), e.message ?: engine.id)
        }
    }

    private fun keyedGet(
        engine: WebSearchSettings.Engine,
        context: Context?,
        url: String,
        headerName: String,
        max: Int,
        parse: (String, Int) -> List<Result>,
        bearer: Boolean = false,
        extra: Map<String, String> = emptyMap(),
    ): Attempt {
        val key = SearchKeyRotator.select(context, engine)
        if (key.isEmpty()) return Attempt(emptyList(), "${engine.id} API key is not configured")
        val headers = linkedMapOf("Accept" to "application/json")
        headers.putAll(extra)
        headers[headerName] = if (bearer && !key.startsWith("Bearer ", ignoreCase = true)) "Bearer $key" else key
        val body = fetchUrl(url, headers, context)
            ?: return Attempt(emptyList(), "empty response from ${engine.id}")
        val parsed = parse(body, max)
        return Attempt(parsed, if (parsed.isEmpty()) "${engine.id} returned no results" else null)
    }

    private fun keyedPost(
        engine: WebSearchSettings.Engine,
        context: Context?,
        url: String,
        body: JSONObject,
        max: Int,
        keyField: String? = null,
        headerName: String? = null,
        bearer: Boolean = false,
        parse: (String, Int) -> List<Result> = ::parseGenericSearchJson,
    ): Attempt {
        val key = SearchKeyRotator.select(context, engine)
        if (key.isEmpty()) return Attempt(emptyList(), "${engine.id} API key is not configured")
        if (keyField != null) body.put(keyField, key)
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        if (headerName != null) headers[headerName] = key
        if (bearer) headers["Authorization"] = if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
        val raw = postJson(url, body.toString(), headers, context)
            ?: return Attempt(emptyList(), "empty response from ${engine.id}")
        val parsed = parse(raw, max)
        return Attempt(parsed, if (parsed.isEmpty()) "${engine.id} returned no results" else null)
    }

    // ── Kelivo provider parsers ─────────────────────────────────────────────

    /** Doubao answers `{Result:{WebResults:[{Title,Url,Summary|Content|Snippet}]}}`. */
    internal fun parseDoubaoJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val root = JSONObject(json)
        val metadata = root.optJSONObject("ResponseMetadata")
        val error = metadata?.optJSONObject("Error")
        if (error != null) {
            throw IllegalStateException(error.optString("Message").ifBlank { error.optString("Code") })
        }
        val arr = root.optJSONObject("Result")?.optJSONArray("WebResults") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("Url").trim()
            if (url.isEmpty()) continue
            val snippet = o.optString("Summary").trim()
                .ifBlank { o.optString("Content").trim() }
                .ifBlank { o.optString("Snippet").trim() }
            out += Result(o.optString("Title").trim(), url, snippet)
            if (out.size >= max) break
        }
        return out
    }

    /** Kimi answers `{search_results:[{title,url,chunks:[{text}]}]}`. */
    internal fun parseKimiJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val arr = JSONObject(json).optJSONArray("search_results") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (url.isEmpty()) continue
            val chunks = o.optJSONArray("chunks")
            val text = buildString {
                if (chunks != null) {
                    for (c in 0 until chunks.length()) {
                        val t = chunks.optJSONObject(c)?.optString("text").orEmpty().trim()
                        if (t.isEmpty()) continue
                        if (isNotEmpty()) append("\n\n")
                        append(t)
                    }
                }
            }
            out += Result(o.optString("title").trim(), url, text.ifBlank { o.optString("snippet").trim() })
            if (out.size >= max) break
        }
        return out
    }

    /**
     * Grok (xAI Responses API) answers with the model's text plus
     * `url_citation` annotations. The answer itself is not a search result, so
     * only the citations become results — that is what the caller can follow.
     */
    internal fun parseGrokJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val seen = HashSet<String>()
        val output = JSONObject(json).optJSONArray("output") ?: return out
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (c in 0 until content.length()) {
                val block = content.optJSONObject(c) ?: continue
                val annotations = block.optJSONArray("annotations") ?: continue
                for (a in 0 until annotations.length()) {
                    val ann = annotations.optJSONObject(a) ?: continue
                    if (ann.optString("type") != "url_citation") continue
                    val url = ann.optString("url").trim()
                    if (url.isEmpty() || !seen.add(url)) continue
                    val title = ann.optString("title").trim().ifBlank { url }
                    out += Result(title, url, "")
                    if (out.size >= max) return out
                }
            }
        }
        return out
    }

    private const val GROK_SYSTEM_PROMPT =
        "You are a search assistant. Answer with the facts you retrieved and cite them."

    // ── Generic parsers ────────────────────────────────────────────────────

    internal fun parseSearxJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val root = JSONObject(json)
        val arr = root.optJSONArray("results") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val title = o.optString("title").trim()
            if (url.isBlank() || title.isBlank()) continue
            out += Result(title, url, o.optString("content").trim())
            if (out.size >= max) break
        }
        return out
    }

    internal fun parseBingJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val pages = JSONObject(json).optJSONObject("webPages") ?: return out
        val arr = pages.optJSONArray("value") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val title = o.optString("name").trim()
            if (url.isBlank() || title.isBlank()) continue
            out += Result(title, url, o.optString("snippet").trim())
            if (out.size >= max) break
        }
        return out
    }

    internal fun expandCustomUrl(template: String, query: String, key: String = ""): String {
        var url = template.trim()
        val q = enc(query)
        val k = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        url = url.replace("{query}", q, ignoreCase = true)
            .replace("{q}", q, ignoreCase = true)
            .replace("{key}", k, ignoreCase = true)
        if (!template.contains("{query}", ignoreCase = true) &&
            !template.contains("{q}", ignoreCase = true)
        ) {
            val sep = if (url.contains('?')) "&" else "?"
            url = "$url${sep}q=$q"
        }
        return url
    }

    /**
     * [T-android-web-search] Walk an arbitrary provider payload for hit arrays.
     *
     * Deliberately a MERGE, not a first-match: Kelivo's You and Firecrawl services
     * both publish `results.web` AND `results.news` (or `data.web` + `data.news`)
     * and their clients show both, so stopping at the first non-empty array would
     * silently drop half of every answer. Results are deduplicated by URL in
     * encounter order, which also keeps `sources`/`references` duplicates out.
     *
     * No early searx/bing short-circuit here: those parsers only read their own
     * snippet field, so letting them win would have thrown away a provider's
     * `excerpts`/`markdown` text before [parseResultArray] saw it.
     */
    internal fun parseGenericSearchJson(json: String, max: Int): List<Result> {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            return parseResultArray(JSONArray(trimmed), max)
        }
        val merged = LinkedHashMap<String, Result>()
        collectResults(JSONObject(json), max, depth = 0, into = merged)
        return merged.values.toList()
    }

    private fun collectResults(
        node: JSONObject,
        max: Int,
        depth: Int,
        into: MutableMap<String, Result>,
    ) {
        if (into.size >= max || depth > 4) return
        for (key in RESULT_KEYS) {
            val arr = node.optJSONArray(key) ?: continue
            for (result in parseResultArray(arr, max)) {
                if (into.size >= max) return
                into.putIfAbsent(result.url, result)
            }
        }
        for (key in RESULT_KEYS) {
            val child = node.optJSONObject(key) ?: continue
            collectResults(child, max, depth + 1, into)
            if (into.size >= max) return
        }
    }

    /**
     * [T-android-web-search] Hit-array keys seen across the provider set:
     * Kelivo's `results`/`items`/`data`/`organic`/`webPages`/`value` plus the
     * ones its extra services use — Kagi's `data.search`, Metaso's `webpages`,
     * LinkUp's `sources`, Querit's `results.result`, You/Firecrawl's
     * `results.web` + `news`.
     */
    private val RESULT_KEYS = arrayOf(
        "results", "items", "data", "organic", "organic_results",
        "webPages", "web", "news", "search_result", "value", "sources",
        "webpages", "search", "result", "references", "citations",
    )


    private fun parseResultArray(arr: JSONArray, max: Int): List<Result> {
        val out = ArrayList<Result>(max)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { o.optString("link") }
                .ifBlank { o.optString("href") }
                .ifBlank { o.optString("displayUrl") }
                .ifBlank { o.optString("Url") }.trim()
            val title = o.optString("title").ifBlank { o.optString("name") }
                .ifBlank { o.optString("Title") }.trim()
            if (url.isBlank() || title.isBlank()) continue
            val snippet = o.optString("snippet").ifBlank { o.optString("content") }
                .ifBlank { o.optString("description") }
                .ifBlank { o.optString("summary") }
                .ifBlank { o.optString("text") }
                // Parallel returns `excerpts` (a list); Firecrawl/You nest
                // content under `markdown` / `contents`.
                .ifBlank {
                    val excerpts = o.optJSONArray("excerpts")
                    if (excerpts != null && excerpts.length() > 0) excerpts.optString(0) else ""
                }
                .ifBlank { o.optString("markdown") }
                .ifBlank { o.optString("sentence") }.trim()
            out += Result(title, url, snippet)
            if (out.size >= max) break
        }
        return out
    }

    private fun searchDuckDuckGo(query: String, max: Int, context: Context?): Attempt {
        val urls = listOf(
            "https://html.duckduckgo.com/html/?q=${enc(query)}",
            "https://lite.duckduckgo.com/lite/?q=${enc(query)}",
        )
        var last = "DuckDuckGo returned no cards"
        var sawBody = false
        for (url in urls) {
            val html = fetchUrl(url, context = context) ?: continue
            sawBody = true
            if (html.contains("anomaly.js") || html.contains("Unfortunately, bots use DuckDuckGo")) {
                last = "DuckDuckGo blocked this client. Set a backend in Settings → Web search."
                continue
            }
            val parsed = parseHtml(html, max)
            if (parsed.isNotEmpty()) return Attempt(parsed, null)
        }
        if (!sawBody) last = "empty response from DuckDuckGo"
        return Attempt(emptyList(), last)
    }

    internal fun parseHtml(html: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val seen = HashSet<String>()
        val resultBlock = Regex(
            // [T-html-text] The class attribute on a real result block is
            // `result results_links results_links_deep web-result` — the previous
            // `class="result(?:__body)?"` required the quote straight after "result"
            // and so never matched a live page, leaving every scrape to the
            // rel="nofollow" fallback. The lookahead keeps `result__a` and
            // `result__snippet` out (they continue with `_`), so the block still
            // starts at the container and not mid-result.
            """class="result(?:__body)?(?=[\s"])[^"]*"[\s\S]{0,2500}?class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>[\s\S]{0,1200}?class="result__snippet"[^>]*>([\s\S]*?)</(?:a|td|div)>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in resultBlock.findAll(html)) {
            val url = decodeDuckLink(HtmlText.decodeEntities(m.groupValues[1]))
            val title = stripTags(m.groupValues[2])
            val snippet = stripTags(m.groupValues[3])
            if (url.isBlank() || title.isBlank()) continue
            if (!seen.add(url)) continue
            out += Result(title, url, snippet)
            if (out.size >= max) return out
        }
        if (out.isNotEmpty()) return out
        val lite = Regex(
            """<a[^>]+rel="nofollow"[^>]+href="(https?://[^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in lite.findAll(html)) {
            val url = HtmlText.decodeEntities(m.groupValues[1])
            if (url.contains("duckduckgo.com", ignoreCase = true)) continue
            val title = stripTags(m.groupValues[2])
            if (url.isBlank() || title.isBlank()) continue
            if (!seen.add(url)) continue
            out += Result(title, url, "")
            if (out.size >= max) break
        }
        return out
    }

    internal fun decodeDuckLink(raw: String): String {
        val href = HtmlText.decodeEntities(raw).trim()
        val normalized = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/l/?") -> "https://duckduckgo.com$href"
            else -> href
        }
        val marker = "uddg="
        val idx = normalized.indexOf(marker)
        if (idx >= 0) {
            val start = idx + marker.length
            val end = normalized.indexOf('&', start).let { if (it < 0) normalized.length else it }
            val encoded = normalized.substring(start, end)
            if (encoded.isNotBlank()) {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            }
        }
        return normalized
    }

    private fun format(query: String, results: List<Result>, engine: String = "ddg"): String = buildString {
        appendLine("web_search ($engine) results for \"$query\" (${results.size}):")
        results.forEachIndexed { i, r ->
            appendLine()
            appendLine("${i + 1}. ${r.title}")
            appendLine("   ${r.url}")
            if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
        }
    }

    private fun enc(query: String): String =
        URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    /**
     * DuckDuckGo HTML often returns a page with no result cards. Wikipedia
     * OpenSearch and public HTML engines need no API key, so they run only
     * after every configured engine returned empty.
     */
    private fun searchNoKeyFallback(query: String, max: Int, context: Context?): List<Result> {
        val out = mutableListOf<Result>()
        out += searchWikipedia(query, max, context)
        if (out.size < max) {
            val bingHtml = fetchUrl("https://www.bing.com/search?q=${enc(query)}&setlang=zh-Hans", context = context)
            if (bingHtml != null) out += parseBingHtml(bingHtml, max - out.size)
        }
        if (out.size < max) {
            out += searchHtmlLinks(
                "https://www.mojeek.com/search?q=${enc(query)}",
                max - out.size,
                context,
                Regex("""<a[^>]+class="[^"]*title[^"]*"[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE),
            )
        }
        return out.distinctBy { it.url }.take(max)
    }

    private fun searchWikipedia(query: String, max: Int, context: Context?): List<Result> {
        val host = if (query.any { it.code > 127 }) "zh.wikipedia.org" else "en.wikipedia.org"
        val url = "https://$host/w/api.php?action=opensearch&search=${enc(query)}&limit=$max&namespace=0&format=json"
        val body = fetchUrl(url, mapOf("Accept" to "application/json"), context) ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            val titles = arr.optJSONArray(1) ?: return emptyList()
            val snippets = arr.optJSONArray(2) ?: JSONArray()
            val urls = arr.optJSONArray(3) ?: JSONArray()
            buildList {
                for (i in 0 until titles.length().coerceAtMost(max)) {
                    val link = urls.optString(i)
                    if (!link.startsWith("http")) continue
                    add(Result(titles.optString(i), link, snippets.optString(i)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun searchHtmlLinks(
        url: String,
        max: Int,
        context: Context?,
        pattern: Regex,
    ): List<Result> {
        if (max <= 0) return emptyList()
        val html = fetchUrl(url, context = context) ?: return emptyList()
        return parseHtmlLinks(html, max, pattern)
    }

    /**
     * [T-android-web-search] Bing's HTML results.
     *
     * Verified against the live endpoint (2026-10-04): Bing now wraps the heading
     * in the link — `<a href="https://…"><h2 …>Title</h2></a>` — where it used to
     * be the other way round (`<h2><a href="…">Title</a></h2>`, the shape Kelivo
     * and the OpenMinis-Linux fork both parse). With only the old pattern, the
     * "Bing is free" engine returned ZERO results from a real response: the page
     * had five result blocks and the regex matched none of them.
     *
     * Both orders are accepted, and the current one is tried first, so a template
     * rollback does not empty the engine again. Bing serves direct URLs here (no
     * `bing.com/ck/` redirect wrapper) — the filter stays as a guard.
     */
    internal fun parseBingHtml(html: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val seen = HashSet<String>()
        val patterns = listOf(
            Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*>\s*<h2[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE),
            Regex("""<h2[^>]*>\s*<a[^>]+href="(https?://[^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(html)) {
                val link = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (!link.startsWith("http")) continue
                if (link.contains("bing.com/ck/") || link.contains("microsoft.com")) continue
                val title = stripTags(match.groupValues.getOrNull(2).orEmpty())
                if (title.isBlank()) continue
                if (!seen.add(link)) continue
                out += Result(title, link, "")
                if (out.size >= max) return out
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    /** Shared by the no-key Bing fallback. */
    internal fun parseHtmlLinks(html: String, max: Int, pattern: Regex): List<Result> {
        if (max <= 0) return emptyList()
        val out = mutableListOf<Result>()
        for (match in pattern.findAll(html)) {
            val link = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (!link.startsWith("http") || link.contains("bing.com/ck/") || link.contains("microsoft.com")) continue
            val title = match.groupValues.getOrNull(2).orEmpty()
            val cleanTitle = stripTags(title)
            if (link.isBlank() || cleanTitle.isBlank()) continue
            out += Result(cleanTitle, link, "")
            if (out.size >= max) break
        }
        return out
    }

    /**
     * [T-android-web-search] The same UA the browser tool sends, taken from
     * [com.openminis.app.browser.UserAgentProfile.MOBILE_CHROME] so the two
     * cannot drift: DuckDuckGo's HTML endpoints and the no-key fallbacks answer
     * a desktop-less mobile Chrome fingerprint, and a hand-rolled string here
     * would be the one place that silently goes stale.
     */
    private fun httpUserAgent(context: Context?): String =
        com.openminis.app.browser.UserAgentProfile.MOBILE_CHROME.userAgentString
            ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private fun fetchUrl(
        urlString: String,
        extraHeaders: Map<String, String> = emptyMap(),
        context: Context? = null,
    ): String? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", httpUserAgent(context))
            setRequestProperty("Accept", extraHeaders["Accept"] ?: "text/html,application/xhtml+xml,application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { inp ->
                BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(
        urlString: String,
        json: String,
        extraHeaders: Map<String, String>,
        context: Context?,
    ): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", httpUserAgent(context))
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { inp ->
                BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun stripTags(raw: String): String =
        HtmlText.decodeEntities(raw.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()


            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}
