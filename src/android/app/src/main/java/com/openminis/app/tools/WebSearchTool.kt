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

    // [T-web-m5] fetch_top bounds: pages, characters shared between them, and time.
    private const val MAX_FETCH_TOP = 3
    private const val EXCERPT_BUDGET_CHARS = 6_000
    private const val EXCERPT_TIMEOUT_SECONDS = 12
    private const val EXCERPT_DEADLINE_SECONDS = 20L
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_SNIPPET_CHARS = 600

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
            "Falls back to DuckDuckGo, then suggest browser_use for a specific URL. " +
            "Optional filters: include_domains / exclude_domains, recency, max_per_domain. " +
            "Set fetch_top to also get a short main-content excerpt of the first results in the same call; " +
            "read a result fully with web_fetch.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Search Android 15 release notes'). Use the same language as the user.",
            ),
            "query" to AgentToolParam("string", "Search query. Be specific; include version numbers or site: filters when useful."),
            "max_results" to AgentToolParam("integer", "How many results to return (default 5, max 8)."),
            "include_domains" to AgentToolParam(
                "string",
                "Only return results from these domains (comma-separated, subdomains included), e.g. 'developer.android.com, kotlinlang.org'.",
            ),
            "exclude_domains" to AgentToolParam(
                "string",
                "Drop results from these domains (comma-separated, subdomains included), e.g. 'pinterest.com, quora.com'.",
            ),
            "recency" to AgentToolParam(
                "string",
                "Only results from the last day, week, month or year. Applied when the search backend supports it; the output says when it could not be.",
                enumValues = listOf("day", "week", "month", "year"),
            ),
            "max_per_domain" to AgentToolParam(
                "integer",
                "At most this many results per site, for a more varied list (default: no cap).",
            ),
            "fetch_top" to AgentToolParam(
                "integer",
                "Also fetch the first N results (0-3, default 0) and append a short main-content excerpt of each. Slower; use it when the snippets will not be enough.",
            ),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "max_results"),
    )

    /** [T-web-m5] The optional filters of one call, resolved once and handed to every engine. */
    internal data class SearchOptions(
        val include: List<String> = emptyList(),
        val exclude: List<String> = emptyList(),
        val recency: String? = null,
        /** Interface language for the scraped engines, see [WebSearchPolicy.searchLanguage]. */
        val language: String = "en",
    )

    private class CachedSearch(val results: List<Result>, val engine: String, val notes: List<String>) {
        fun weight(): Long {
            var w = 64L
            for (r in results) w += r.title.length + r.url.length + r.snippet.length
            return w
        }
    }

    /** Same query + same options within ten minutes: answer from memory, spend no quota. */
    private val searchCache: WebCache<CachedSearch> = WebCache(
        maxEntries = 48,
        maxWeight = 400_000L,
        ttlMs = 10L * 60L * 1000L,
        sizeOf = { it.weight() },
    )

    private val cooldown = EngineCooldown()

    /** Test seam: forget cached searches and engine cooldowns. */
    internal fun resetState() {
        searchCache.clear()
        cooldown.reset()
    }

    internal fun searchCacheKey(
        engineId: String,
        allowFallback: Boolean,
        max: Int,
        maxPerDomain: Int,
        options: SearchOptions,
        query: String,
    ): String = listOf(
        engineId,
        if (allowFallback) "fb" else "nofb",
        max.toString(),
        maxPerDomain.toString(),
        options.include.sorted().joinToString(","),
        options.exclude.sorted().joinToString(","),
        options.recency ?: "-",
        options.language,
        query.trim().lowercase().replace(Regex("\\s+"), " "),
    ).joinToString("|")

    private fun stringArg(args: JSONObject, key: String): String =
        if (!args.has(key) || args.isNull(key)) "" else args.opt(key)?.toString().orEmpty()

    fun execute(argsJson: String, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val query = args.optString("query", "").trim()
            val toolTitle = args.optString("tool_title", NAME)
            val max = args.optInt("max_results", 5).coerceIn(1, MAX_RESULTS)
            if (query.isEmpty()) {
                return ToolExecutionResult("query is required", success = false, toolTitle = toolTitle)
            }
            val include = WebSearchPolicy.parseDomainList(stringArg(args, "include_domains"))
            val exclude = WebSearchPolicy.parseDomainList(stringArg(args, "exclude_domains"))
            val recency = WebSearchPolicy.normalizeRecency(stringArg(args, "recency"))
            val maxPerDomain = args.optInt("max_per_domain", 0).coerceIn(0, MAX_RESULTS)
            val fetchTop = args.optInt("fetch_top", 0).coerceIn(0, MAX_FETCH_TOP)
            val filtering = include.isNotEmpty() || exclude.isNotEmpty() || maxPerDomain > 0
            val want = WebSearchPolicy.requestCount(max, filtering)
            val deviceLanguage = try {
                java.util.Locale.getDefault().toLanguageTag()
            } catch (_: Exception) {
                null
            }
            val options = SearchOptions(
                include = include,
                exclude = exclude,
                recency = recency,
                language = WebSearchPolicy.searchLanguage(query, deviceLanguage),
            )

            val preferred = context?.let { WebSearchSettings.engine(it) } ?: WebSearchSettings.Engine.DDG
            val allowFallback = context?.let { WebSearchSettings.fallbackEnabled(it) } ?: true
            val cacheKey = searchCacheKey(preferred.id, allowFallback, max, maxPerDomain, options, query)

            var results: List<Result> = emptyList()
            var usedLabel = preferred.id
            val notes = ArrayList<String>()
            var cachedAgeMs: Long? = null

            val hit = searchCache.get(cacheKey)
            if (hit != null) {
                results = hit.value.results
                usedLabel = hit.value.engine
                notes.addAll(hit.value.notes)
                cachedAgeMs = hit.ageMs
            } else {
                // A user who picked a no-key engine must not silently spend keyed
                // quotas when it returns nothing. Keyed fallback only runs after a
                // KEYED engine was the one they asked for. The key-less engines
                // (DuckDuckGo, Bing HTML) follow in both cases.
                val configured = if (context != null) WebSearchSettings.configuredKeyed(context).map { it.id } else emptyList()
                val order = WebSearchPolicy.engineOrder(
                    preferred = preferred.id,
                    preferredNeedsKey = preferred.needsKey,
                    configuredKeyed = configured,
                    allowFallback = allowFallback,
                    coolingDown = cooldown.cooling(),
                )
                val failures = ArrayList<String>()
                var filteredAway = 0
                var used: WebSearchSettings.Engine? = null
                for (engineId in order) {
                    val engine = WebSearchSettings.Engine.entries.firstOrNull { it.id == engineId } ?: continue
                    val attempt = search(engine, query, want, context, options)
                    if (attempt.results.isEmpty()) {
                        val reason = attempt.error ?: "no results"
                        failures += "${engine.id}: $reason"
                        if (!reason.contains("not configured")) cooldown.recordFailure(engine.id)
                        continue
                    }
                    cooldown.recordSuccess(engine.id)
                    val refined = WebSearchPolicy.refine(attempt.results, include, exclude, maxPerDomain, max)
                    if (refined.isEmpty()) {
                        filteredAway += attempt.results.size
                        failures += "${engine.id}: ${attempt.results.size} results, none matched the domain filters"
                        continue
                    }
                    results = refined
                    used = engine
                    break
                }
                if (results.isEmpty()) {
                    val skipBing = WebSearchSettings.Engine.BING_LOCAL.id in order
                    val fallback = WebSearchPolicy.refine(
                        searchNoKeyFallback(query, want, context, options, skipBing),
                        include, exclude, maxPerDomain, max,
                    )
                    if (fallback.isEmpty()) {
                        val filterHint = if (filteredAway > 0) {
                            " $filteredAway results were found but none matched include_domains/exclude_domains — loosen the filters."
                        } else {
                            ""
                        }
                        return ToolExecutionResult(
                            "web_search failed for \"$query\" via ${preferred.id}: ${failures.firstOrNull()?.substringAfter(": ") ?: "no results"}. " +
                                "DuckDuckGo HTML returned no cards, and the no-key Wikipedia/Bing/Mojeek fallback was also empty. " +
                                "Tried: ${failures.joinToString("; ").take(700)}.$filterHint " +
                                "Configure Settings → Web search, or open a known URL with browser_use.",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    results = fallback
                    usedLabel = "no-key-fallback"
                    if (recency != null) notes += "recency=$recency could not be applied by the fallback engines; results are not time-filtered."
                } else if (used != null) {
                    usedLabel = used.id
                    if (used != preferred) {
                        val why = failures.firstOrNull { it.startsWith(preferred.id + ":") }?.substringAfter(": ") ?: "no results"
                        notes += "${preferred.id} did not answer ($why); these results are from ${used.id}."
                    }
                    if (recency != null && WebSearchPolicy.recencyToken(used.id, recency) == null) {
                        notes += "recency=$recency is not supported by ${used.id}; results are not time-filtered."
                    }
                }
                searchCache.put(cacheKey, CachedSearch(results, usedLabel, notes.toList()))
            }

            val output = StringBuilder(format(query, results, usedLabel))
            if (cachedAgeMs != null) notes += "served from the in-memory cache (searched ${cachedAgeMs / 1000}s ago)."
            if (notes.isNotEmpty()) {
                output.appendLine()
                for (note in notes) output.appendLine("Note: $note")
            }
            if (fetchTop > 0) output.append(excerptsSection(results, fetchTop))
            ToolExecutionResult(output.toString(), success = true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("web_search failed: ${e.message}", success = false)
        }
    }

    /**
     * [T-web-m5] `fetch_top`: read the first [count] results in parallel through
     * [WebFetchTool.excerpt] — the same SSRF-guarded client as `web_fetch`, never
     * a third-party reader service — and append a main-content excerpt of each.
     * The total is budgeted ([EXCERPT_BUDGET_CHARS] shared between the pages) and
     * the whole step has one deadline; a page that fails or is late is skipped.
     */
    private fun excerptsSection(results: List<Result>, count: Int): String {
        val targets = results.take(count)
        if (targets.isEmpty()) return ""
        val share = WebPaging.shareBudget(EXCERPT_BUDGET_CHARS, targets.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(targets.size)
        val windows = arrayOfNulls<WebPaging.Window>(targets.size)
        try {
            val tasks = targets.map { r ->
                java.util.concurrent.Callable<WebPaging.Window?> {
                    try {
                        WebFetchTool.excerpt(r.url, share, EXCERPT_TIMEOUT_SECONDS)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            val futures = pool.invokeAll(tasks, EXCERPT_DEADLINE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            futures.forEachIndexed { index, future ->
                windows[index] = try {
                    if (future.isCancelled) null else future.get()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            // Interrupted or rejected: return what we have.
        } finally {
            pool.shutdownNow()
        }
        return buildString {
            appendLine()
            appendLine("Excerpts of the first ${targets.size} results (main content as Markdown):")
            targets.forEachIndexed { index, r ->
                appendLine()
                appendLine("[${index + 1}] ${clip(r.title, MAX_TITLE_CHARS)} — ${r.url}")
                val w = windows[index]
                if (w == null) {
                    appendLine("(could not be fetched — try web_fetch on this URL, with render=true if it is a JavaScript app)")
                } else {
                    appendLine(w.content.trim())
                    if (w.hasMore) {
                        appendLine(
                            "[excerpt: characters 0–${w.end} of ${w.total} — read on with web_fetch, " +
                                "this url, format=markdown, offset=${w.end}]",
                        )
                    }
                }
            }
        }
    }

    private data class Attempt(val results: List<Result>, val error: String?)

    /** A non-2xx answer from a search backend, kept typed so key rotation can react to it. */
    internal class SearchHttpException(val code: Int, message: String) : Exception(message)

    private fun scrapeHeaders(options: SearchOptions): Map<String, String> =
        mapOf("Accept-Language" to WebSearchPolicy.acceptLanguage(options.language))

    private fun search(
        engine: WebSearchSettings.Engine,
        rawQuery: String,
        max: Int,
        context: Context?,
        options: SearchOptions = SearchOptions(),
    ): Attempt {
        // Engines that understand `site:` get the domain filters as operators;
        // the rest see the plain query and are post-filtered.
        val query = WebSearchPolicy.queryWithDomains(engine.id, rawQuery, options.include, options.exclude)
        val recency = WebSearchPolicy.recencyToken(engine.id, options.recency)
        return try {
            when (engine) {
                WebSearchSettings.Engine.DDG -> searchDuckDuckGo(query, max, context, options, recency)

                // [T-android-web-search] Bing's public HTML endpoint: no key, and
                // the reason "Bing is free" — the paid Web Search API is a
                // separate engine (BING) for users who have a subscription key.
                WebSearchSettings.Engine.BING_LOCAL -> {
                    if (max <= 0) return Attempt(emptyList(), null)
                    val html = fetchUrl(
                        "https://www.bing.com/search?q=${enc(query)}&setlang=${enc(options.language)}",
                        scrapeHeaders(options),
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
                    val timeRange = if (recency != null) "&time_range=$recency" else ""
                    val body = fetchUrl("$endpoint?q=${enc(query)}&format=json$timeRange", headers, context)
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
                        .put("search_depth", "basic")
                        .also { b ->
                            if (recency != null) b.put("time_range", recency)
                            if (options.include.isNotEmpty()) b.put("include_domains", JSONArray(options.include))
                            if (options.exclude.isNotEmpty()) b.put("exclude_domains", JSONArray(options.exclude))
                        },
                    keyField = "api_key",
                    max = max,
                )

                WebSearchSettings.Engine.BING -> keyedGet(
                    engine, context,
                    url = "${engine.resolvedUrl(context)}?q=${enc(query)}&count=$max" +
                        (if (recency != null) "&freshness=$recency" else ""),
                    headerName = "Ocp-Apim-Subscription-Key",
                    max = max,
                    parse = ::parseBingJson,
                )

                WebSearchSettings.Engine.BRAVE -> keyedGet(
                    engine, context,
                    url = "${engine.resolvedUrl(context)}?q=${enc(query)}&count=$max" +
                        (if (recency != null) "&freshness=$recency" else ""),
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
                        .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 400)))
                        .also { b ->
                            val window = options.recency
                            if (window != null) {
                                b.put("startPublishedDate", WebSearchPolicy.recencyStartIso(window, System.currentTimeMillis()))
                            }
                            // Exa accepts one of the two lists per request.
                            if (options.include.isNotEmpty()) {
                                b.put("includeDomains", JSONArray(options.include))
                            } else if (options.exclude.isNotEmpty()) {
                                b.put("excludeDomains", JSONArray(options.exclude))
                            }
                        },
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
                    body = JSONObject().put("q", query).put("num", max)
                        .also { b -> if (recency != null) b.put("tbs", recency) },
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

    /**
     * [T-web-m5] Run [request] with this engine's next key; when the backend
     * rejects THAT KEY (401/403) or its quota (429) and the pool has another
     * one, try the next — the point of a key pool. At most three keys per call.
     */
    private fun withKeyRotation(
        engine: WebSearchSettings.Engine,
        context: Context?,
        request: (key: String) -> Attempt,
    ): Attempt {
        val poolSize = WebSearchSettings.keys(context, engine).size
        if (poolSize == 0) return Attempt(emptyList(), "${engine.id} API key is not configured")
        val attempts = poolSize.coerceAtMost(3)
        var last: SearchHttpException? = null
        for (i in 0 until attempts) {
            val key = SearchKeyRotator.select(context, engine)
            if (key.isEmpty()) return Attempt(emptyList(), "${engine.id} API key is not configured")
            try {
                return request(key)
            } catch (e: SearchHttpException) {
                last = e
                if (e.code != 401 && e.code != 403 && e.code != 429) throw e
            }
        }
        throw last ?: IllegalStateException("${engine.id} request failed")
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
    ): Attempt = withKeyRotation(engine, context) { key ->
        val headers = linkedMapOf("Accept" to "application/json")
        headers.putAll(extra)
        headers[headerName] = if (bearer && !key.startsWith("Bearer ", ignoreCase = true)) "Bearer $key" else key
        val body = fetchUrl(url, headers, context)
        if (body == null) {
            Attempt(emptyList(), "empty response from ${engine.id}")
        } else {
            val parsed = parse(body, max)
            Attempt(parsed, if (parsed.isEmpty()) "${engine.id} returned no results" else null)
        }
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
    ): Attempt = withKeyRotation(engine, context) { key ->
        if (keyField != null) body.put(keyField, key)
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        if (headerName != null) headers[headerName] = key
        if (bearer) headers["Authorization"] = if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
        val raw = postJson(url, body.toString(), headers, context)
        if (raw == null) {
            Attempt(emptyList(), "empty response from ${engine.id}")
        } else {
            val parsed = parse(raw, max)
            Attempt(parsed, if (parsed.isEmpty()) "${engine.id} returned no results" else null)
        }
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

    private fun searchDuckDuckGo(
        query: String,
        max: Int,
        context: Context?,
        options: SearchOptions = SearchOptions(),
        recency: String? = null,
    ): Attempt {
        // `df` is DuckDuckGo's date filter (d/w/m/y) on both HTML faces.
        val df = if (recency != null) "&df=$recency" else ""
        val urls = listOf(
            "https://html.duckduckgo.com/html/?q=${enc(query)}$df",
            "https://lite.duckduckgo.com/lite/?q=${enc(query)}$df",
        )
        var last = "DuckDuckGo returned no cards"
        var sawBody = false
        for (url in urls) {
            val html: String? = try {
                fetchUrl(url, scrapeHeaders(options), context)
            } catch (e: SearchHttpException) {
                last = "DuckDuckGo: ${e.message}"
                sawBody = true
                null
            }
            if (html == null) continue
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
            appendLine("${i + 1}. ${clip(r.title, MAX_TITLE_CHARS)}")
            appendLine("   ${r.url}")
            // Bounded: some backends return whole page text as the "snippet".
            if (r.snippet.isNotBlank()) appendLine("   ${clip(r.snippet, MAX_SNIPPET_CHARS)}")
        }
    }

    /** One line, at most [max] characters, with an ellipsis when cut. */
    internal fun clip(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }

    private fun enc(query: String): String =
        URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    /**
     * DuckDuckGo HTML often returns a page with no result cards. Wikipedia
     * OpenSearch and public HTML engines need no API key, so they run only
     * after every configured engine returned empty.
     */
    private fun searchNoKeyFallback(
        query: String,
        max: Int,
        context: Context?,
        options: SearchOptions = SearchOptions(),
        skipBing: Boolean = false,
    ): List<Result> {
        val out = mutableListOf<Result>()
        // [T-web-m5] Real web engines first, the encyclopedia last: Wikipedia's
        // title search used to lead this list and pushed actual web results
        // for the query out of a short page. Each step is isolated — one
        // engine answering 403 must not cancel the ones after it.
        if (!skipBing) {
            try {
                val bingHtml = fetchUrl(
                    "https://www.bing.com/search?q=${enc(query)}&setlang=${enc(options.language)}",
                    scrapeHeaders(options),
                    context,
                )
                if (bingHtml != null) out += parseBingHtml(bingHtml, max)
            } catch (_: Exception) {
            }
        }
        if (out.size < max) {
            try {
                out += searchHtmlLinks(
                    "https://www.mojeek.com/search?q=${enc(query)}",
                    max - out.size,
                    context,
                    Regex("""<a[^>]+class="[^"]*title[^"]*"[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE),
                )
            } catch (_: Exception) {
            }
        }
        if (out.size < max) {
            try {
                out += searchWikipedia(query, max - out.size, context, options.language)
            } catch (_: Exception) {
            }
        }
        return out.distinctBy { it.url }.take(max)
    }

    private fun searchWikipedia(query: String, max: Int, context: Context?, language: String = "en"): List<Result> {
        if (max <= 0) return emptyList()
        val host = wikipediaHost(language)
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

    /**
     * Wikipedia edition for the search language. It used to be "any non-ASCII
     * character means Chinese", which sent a Vietnamese or French query to
     * zh.wikipedia.org.
     */
    internal fun wikipediaHost(language: String): String {
        val lang = language.lowercase()
        val code = when {
            lang.startsWith("zh") -> "zh"
            lang.length in 2..3 && lang.all { it in 'a'..'z' } -> lang
            else -> lang.substringBefore('-').ifBlank { "en" }
        }
        return "$code.wikipedia.org"
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
            val matches = pattern.findAll(html).toList()
            for ((index, match) in matches.withIndex()) {
                val rawLink = HtmlText.decodeEntities(match.groupValues.getOrNull(1)?.trim().orEmpty())
                if (!rawLink.startsWith("http")) continue
                // [T-web-m5] A click tracker hides a real result: unwrap it when
                // the target can be read, skip it (as before) when it cannot.
                val link = if (rawLink.contains("bing.com/ck/")) decodeBingTracker(rawLink) ?: continue else rawLink
                if (link.contains("bing.com/ck/") || link.contains("microsoft.com")) continue
                val title = stripTags(match.groupValues.getOrNull(2).orEmpty())
                if (title.isBlank()) continue
                if (!seen.add(link)) continue
                // [T-web-m5] The caption paragraph between this hit and the next
                // one is its snippet; the engine used to return titles only.
                val from = match.range.last + 1
                val until = if (index + 1 < matches.size) matches[index + 1].range.first else html.length
                val scopeStart = minOf(from, html.length)
                val scopeEnd = minOf(until, scopeStart + 3000, html.length).coerceAtLeast(scopeStart)
                val scope = html.substring(scopeStart, scopeEnd)
                val snippet = BING_CAPTION.find(scope)?.groupValues?.getOrNull(1)?.let { stripTags(it) }.orEmpty()
                out += Result(title, link, snippet)
                if (out.size >= max) return out
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    private val BING_CAPTION = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)

    /**
     * Bing click tracker `…/ck/a?…&u=a1<base64url of the target>` → the target,
     * or null when the link carries no readable target.
     */
    internal fun decodeBingTracker(link: String): String? {
        val m = Regex("[?&]u=a1([A-Za-z0-9_-]+)").find(link) ?: return null
        return try {
            val decoded = String(java.util.Base64.getUrlDecoder().decode(m.groupValues[1]), StandardCharsets.UTF_8)
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        } catch (_: Exception) {
            null
        }
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
    ): String? = exchange("GET", urlString, null, extraHeaders, context)

    private fun postJson(
        urlString: String,
        json: String,
        extraHeaders: Map<String, String>,
        context: Context?,
    ): String? = exchange("POST", urlString, json, extraHeaders, context)

    /**
     * One request to a search backend.
     *
     * [T-web-m5] A non-2xx answer is no longer handed to the parser as if it
     * were a result page (which turned "HTTP 429" and "HTTP 401" into the
     * useless "returned no results"): it is thrown as [SearchHttpException]
     * with the status and the start of the body, after ONE short retry for the
     * statuses that describe the moment (429/502/503/504).
     */
    private fun exchange(
        method: String,
        urlString: String,
        json: String?,
        extraHeaders: Map<String, String>,
        context: Context?,
    ): String? {
        var attempt = 0
        while (true) {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = method
                setRequestProperty("User-Agent", httpUserAgent(context))
                if (json == null) {
                    setRequestProperty("Accept", extraHeaders["Accept"] ?: "text/html,application/xhtml+xml,application/json")
                    setRequestProperty("Accept-Language", extraHeaders["Accept-Language"] ?: "zh-CN,zh;q=0.9,en;q=0.8")
                } else {
                    doOutput = true
                }
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                if (json != null) {
                    conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { inp -> readCapped(inp) }
                if (code in 200..299) return body
                val retryAfter = conn.getHeaderField("Retry-After")
                if (attempt == 0 && HttpRetryPolicy.isRetryableStatus(code)) {
                    val wait = HttpRetryPolicy.delayMs(1, retryAfter)
                    if (wait != null && wait <= 2_000L) {
                        attempt = 1
                        try {
                            Thread.sleep(wait)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw SearchHttpException(code, HttpRetryPolicy.describe(code, retryAfter))
                        }
                        continue
                    }
                }
                val detail = clip(body.orEmpty(), 160)
                throw SearchHttpException(
                    code,
                    HttpRetryPolicy.describe(code, retryAfter) + if (detail.isNotEmpty()) ": $detail" else "",
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    /** At most [MAX_RESPONSE_CHARS] of a backend answer: a search page is never larger. */
    private fun readCapped(input: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        while (sb.length < MAX_RESPONSE_CHARS) {
            val n = reader.read(buffer)
            if (n < 0) break
            sb.append(buffer, 0, n)
        }
        return sb.toString()
    }

    private const val MAX_RESPONSE_CHARS = 2_000_000

    private fun stripTags(raw: String): String =
        HtmlText.decodeEntities(raw.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()


            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}
