package com.openminis.app.tools

/**
 * [T-web-m5] Pure post-processing and request-shaping rules for [WebSearchTool]:
 * what happens to a result list after an engine answered (dedup, domain
 * filters, per-domain cap), how the optional filters are spelled for each
 * engine, and which engines are tried in which order.
 *
 * Everything here is deterministic and free of Android/network types, so the
 * rules are unit-tested directly (WebSearchPolicyTest).
 */
object WebSearchPolicy {

    const val MAX_DOMAIN_FILTERS = 8

    // ── domain lists ────────────────────────────────────────────────────────

    /**
     * `include_domains` / `exclude_domains` as the model writes them: a comma,
     * space, semicolon or newline separated string, or a JSON-ish array
     * (`["a.com","b.org"]`). Returns bare hosts, de-duplicated, at most
     * [MAX_DOMAIN_FILTERS].
     */
    fun parseDomainList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (part in raw.split(Regex("[\\s,;\\[\\]\"']+"))) {
            val domain = WebUrlCanonicalizer.normalizeDomain(part)
            if (domain.isNotEmpty()) out.add(domain)
            if (out.size >= MAX_DOMAIN_FILTERS) break
        }
        return out.toList()
    }

    fun allowedByDomains(url: String, include: List<String>, exclude: List<String>): Boolean {
        if (include.isEmpty() && exclude.isEmpty()) return true
        val host = WebUrlCanonicalizer.host(url)
        if (exclude.any { WebUrlCanonicalizer.hostMatches(host, it) }) return false
        if (include.isEmpty()) return true
        return include.any { WebUrlCanonicalizer.hostMatches(host, it) }
    }

    // ── result list ─────────────────────────────────────────────────────────

    /**
     * Drop rows that are the same document under another spelling. First seen
     * wins and keeps its position; a later duplicate only donates its snippet
     * when the kept row has none (engines disagree about who has the text).
     * Tracking parameters are removed from the URL the model sees.
     */
    fun dedupe(results: List<WebSearchTool.Result>): List<WebSearchTool.Result> {
        val byKey = LinkedHashMap<String, WebSearchTool.Result>()
        for (r in results) {
            val url = WebUrlCanonicalizer.stripTracking(r.url)
            if (url.isBlank()) continue
            val key = WebUrlCanonicalizer.key(url)
            val kept = byKey[key]
            if (kept == null) {
                byKey[key] = if (url == r.url) r else r.copy(url = url)
            } else if (kept.snippet.isBlank() && r.snippet.isNotBlank()) {
                byKey[key] = kept.copy(snippet = r.snippet)
            }
        }
        return byKey.values.toList()
    }

    /**
     * At most [maxPerDomain] rows per host, order otherwise preserved.
     * `maxPerDomain <= 0` means no cap. When the cap leaves fewer than [limit]
     * rows, the rows it held back are appended in their original order: the cap
     * exists to diversify a full page, never to return less than was found.
     */
    fun capPerDomain(
        results: List<WebSearchTool.Result>,
        maxPerDomain: Int,
        limit: Int,
    ): List<WebSearchTool.Result> {
        if (maxPerDomain <= 0) return results.take(limit.coerceAtLeast(0))
        val counts = HashMap<String, Int>()
        val kept = ArrayList<WebSearchTool.Result>()
        val held = ArrayList<WebSearchTool.Result>()
        for (r in results) {
            val host = WebUrlCanonicalizer.host(r.url)
            val n = counts[host] ?: 0
            if (n < maxPerDomain) {
                counts[host] = n + 1
                kept += r
            } else {
                held += r
            }
        }
        if (kept.size < limit) kept += held.take(limit - kept.size)
        return kept.take(limit.coerceAtLeast(0))
    }

    /** The whole pipeline, in the one order that makes sense. */
    fun refine(
        results: List<WebSearchTool.Result>,
        include: List<String>,
        exclude: List<String>,
        maxPerDomain: Int,
        limit: Int,
    ): List<WebSearchTool.Result> {
        val filtered = dedupe(results).filter { allowedByDomains(it.url, include, exclude) }
        return capPerDomain(filtered, maxPerDomain, limit)
    }

    /**
     * How many rows to ask an engine for. Unchanged ([limit]) for a plain
     * search, so quota use is what it always was; doubled (bounded) only when a
     * filter or cap is going to throw rows away afterwards.
     */
    fun requestCount(limit: Int, filtering: Boolean, hardMax: Int = 16): Int =
        if (filtering) (limit * 2).coerceIn(limit, hardMax.coerceAtLeast(limit)) else limit

    // ── recency ─────────────────────────────────────────────────────────────

    val RECENCY_VALUES: List<String> = listOf("day", "week", "month", "year")

    /** A typo degrades to "no filter"; it must never break the search. */
    fun normalizeRecency(raw: String?): String? {
        val v = raw?.trim()?.lowercase().orEmpty()
        return when (v) {
            "day", "d", "24h", "today" -> "day"
            "week", "w", "7d" -> "week"
            "month", "m", "30d" -> "month"
            "year", "y", "365d" -> "year"
            else -> null
        }
    }

    /**
     * The engine's own spelling of [recency], or null when that engine has no
     * time filter we can rely on (the caller then says so in the output instead
     * of pretending the results were filtered).
     */
    fun recencyToken(engineId: String, recency: String?): String? {
        if (recency == null) return null
        return when (engineId) {
            "ddg" -> when (recency) { "day" -> "d"; "week" -> "w"; "month" -> "m"; "year" -> "y"; else -> null }
            "brave" -> when (recency) { "day" -> "pd"; "week" -> "pw"; "month" -> "pm"; "year" -> "py"; else -> null }
            "tavily", "searxng" -> recency
            "serper" -> when (recency) { "day" -> "qdr:d"; "week" -> "qdr:w"; "month" -> "qdr:m"; "year" -> "qdr:y"; else -> null }
            "bing" -> when (recency) { "day" -> "Day"; "week" -> "Week"; "month" -> "Month"; else -> null }
            // Exa takes a start date, computed by [recencyStartIso].
            "exa" -> recency
            else -> null
        }
    }

    fun recencyDays(recency: String): Int = when (recency) {
        "day" -> 1
        "week" -> 7
        "month" -> 30
        else -> 365
    }

    /** `2026-11-17T00:00:00.000Z`-style UTC start of the window ending at [nowMs]. */
    fun recencyStartIso(recency: String, nowMs: Long): String {
        val start = nowMs - recencyDays(recency) * 86_400_000L
        val days = Math.floorDiv(start, 86_400_000L)
        // Civil-from-days (proleptic Gregorian), so no java.time / timezone is involved.
        val z = days + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val year = if (m <= 2L) y + 1L else y
        return "%04d-%02d-%02dT00:00:00.000Z".format(year, m, d)
    }

    // ── query operators ─────────────────────────────────────────────────────

    /** Engines whose query box understands `site:` / `-site:`. */
    private val OPERATOR_ENGINES: Set<String> = setOf(
        "ddg", "bing_local", "bing", "brave", "serper", "searxng", "kagi",
    )

    /** Engines that take the domain lists as native request fields instead. */
    fun hasNativeDomainFilter(engineId: String): Boolean = engineId == "tavily" || engineId == "exa"

    /**
     * [query] with the domain filters spelled as search operators, for engines
     * that understand them. Other engines get the query untouched and rely on
     * the post-filter. A query that already carries a `site:` operator is left
     * alone — the model was explicit, and two competing scopes return nothing.
     */
    fun queryWithDomains(
        engineId: String,
        query: String,
        include: List<String>,
        exclude: List<String>,
    ): String {
        if (engineId !in OPERATOR_ENGINES) return query
        if (include.isEmpty() && exclude.isEmpty()) return query
        if (Regex("(?i)(^|\\s)-?site:").containsMatchIn(query)) return query
        val sb = StringBuilder(query.trim())
        val inc = include.take(4)
        if (inc.size == 1) {
            sb.append(" site:").append(inc[0])
        } else if (inc.size > 1) {
            sb.append(" (").append(inc.joinToString(" OR ") { "site:$it" }).append(")")
        }
        for (d in exclude.take(4)) sb.append(" -site:").append(d)
        return sb.toString()
    }

    // ── language ────────────────────────────────────────────────────────────

    /**
     * Interface language for the scraped engines. It used to be hard-wired to
     * Simplified Chinese, which skews every non-Chinese query. The query's own
     * script wins (a CJK query wants CJK pages whatever the phone is set to),
     * then the device language, then English.
     */
    fun searchLanguage(query: String, deviceLanguageTag: String?): String {
        var han = 0
        var kana = 0
        var hangul = 0
        for (ch in query) {
            val c = ch.code
            when {
                c in 0x3040..0x30FF -> kana++
                c in 0xAC00..0xD7AF -> hangul++
                c in 0x3400..0x9FFF -> han++
            }
        }
        if (kana > 0) return "ja"
        if (hangul > 0) return "ko"
        val tag = deviceLanguageTag?.trim().orEmpty().replace('_', '-')
        if (han > 0) {
            return if (tag.startsWith("zh", ignoreCase = true) && tag.isNotEmpty()) bingLang(tag) else "zh-Hans"
        }
        if (tag.isEmpty()) return "en"
        return bingLang(tag)
    }

    private fun bingLang(tag: String): String {
        val lower = tag.lowercase()
        if (lower.startsWith("zh")) {
            return if (lower.contains("tw") || lower.contains("hk") || lower.contains("hant")) "zh-Hant" else "zh-Hans"
        }
        val lang = lower.substringBefore('-')
        return if (lang.length in 2..3 && lang.all { it in 'a'..'z' }) lang else "en"
    }

    /** `Accept-Language` matching [searchLanguage]; English always stays acceptable. */
    fun acceptLanguage(language: String): String = when {
        language.startsWith("zh-Hant") -> "zh-TW,zh;q=0.9,en;q=0.8"
        language.startsWith("zh") -> "zh-CN,zh;q=0.9,en;q=0.8"
        language == "en" -> "en-US,en;q=0.9"
        else -> "$language,en;q=0.8"
    }

    // ── engine order ────────────────────────────────────────────────────────

    /**
     * Engines to try, in order, as engine ids.
     *
     *  1. the engine the user chose — always, even if it failed a moment ago;
     *  2. when fallback is on and the chosen engine is a KEYED one: the other
     *     keyed engines that have a key. A user who picked a no-key engine never
     *     silently spends keyed quota;
     *  3. when fallback is on: the key-less web engines, DuckDuckGo then Bing's
     *     HTML endpoint.
     *
     * [coolingDown] engines are skipped in steps 2–3 (a backend that just
     * blocked us costs a full timeout to ask again) — unless that would leave
     * nothing but the chosen engine, in which case they are tried anyway:
     * the cooldown may only make a search faster, never emptier.
     */
    fun engineOrder(
        preferred: String,
        preferredNeedsKey: Boolean,
        configuredKeyed: List<String>,
        allowFallback: Boolean,
        coolingDown: Set<String> = emptySet(),
    ): List<String> {
        val order = ArrayList<String>()
        order += preferred
        if (!allowFallback) return order
        val candidates = ArrayList<String>()
        if (preferredNeedsKey) {
            for (id in configuredKeyed) if (id != preferred && id !in candidates) candidates += id
        }
        for (id in KEYLESS_CHAIN) if (id != preferred && id !in candidates) candidates += id
        val usable = candidates.filter { it !in coolingDown }
        order += if (usable.isEmpty()) candidates else usable
        return order
    }

    val KEYLESS_CHAIN: List<String> = listOf("ddg", "bing_local")
}

/**
 * [T-web-m5] Per-engine cooldown after repeated failures, in memory.
 *
 * Two consecutive failures rest an engine for [baseMs]; each further failure
 * doubles the rest up to [maxMs]; one success clears it. Time is injected so
 * the rule is testable without sleeping.
 */
class EngineCooldown(
    private val baseMs: Long = 60_000L,
    private val maxMs: Long = 600_000L,
    private val threshold: Int = 2,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class State(var failures: Int, var until: Long)

    private val states = HashMap<String, State>()

    @Synchronized
    fun recordSuccess(engineId: String) {
        states.remove(engineId)
    }

    @Synchronized
    fun recordFailure(engineId: String) {
        val s = states.getOrPut(engineId) { State(0, 0L) }
        s.failures += 1
        if (s.failures >= threshold) {
            val shift = (s.failures - threshold).coerceAtMost(20)
            val rest = (baseMs shl shift).coerceAtMost(maxMs).coerceAtLeast(0L)
            s.until = clock() + rest
        }
    }

    @Synchronized
    fun isCooling(engineId: String): Boolean {
        val s = states[engineId] ?: return false
        return s.until > clock()
    }

    @Synchronized
    fun cooling(): Set<String> {
        val now = clock()
        return states.filter { it.value.until > now }.keys.toSet()
    }

    @Synchronized
    fun reset() {
        states.clear()
    }
}
