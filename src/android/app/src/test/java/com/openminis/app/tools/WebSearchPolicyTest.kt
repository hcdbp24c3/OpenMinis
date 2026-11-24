package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-web-m5] Result post-processing, filter spelling per engine, and engine order. */
class WebSearchPolicyTest {

    private fun r(url: String, title: String = "t", snippet: String = ""): WebSearchTool.Result =
        WebSearchTool.Result(title, url, snippet)

    // ── dedupe ──────────────────────────────────────────────────────────────

    @Test
    fun `duplicates under another spelling collapse to the first row`() {
        val out = WebSearchPolicy.dedupe(
            listOf(
                r("https://example.com/a", "first"),
                r("http://www.example.com/a/?utm_source=x", "second"),
                r("https://example.com/b", "third"),
            ),
        )
        assertEquals(listOf("first", "third"), out.map { it.title })
    }

    @Test
    fun `a duplicate donates its snippet when the kept row has none`() {
        val out = WebSearchPolicy.dedupe(
            listOf(r("https://example.com/a", "first", ""), r("https://example.com/a/", "dup", "the text")),
        )
        assertEquals(1, out.size)
        assertEquals("first", out[0].title)
        assertEquals("the text", out[0].snippet)
    }

    @Test
    fun `shown urls lose tracking parameters and blank urls are dropped`() {
        val out = WebSearchPolicy.dedupe(listOf(r("https://example.com/a?id=1&utm_source=x"), r("  ")))
        assertEquals(listOf("https://example.com/a?id=1"), out.map { it.url })
    }

    // ── domain filters ──────────────────────────────────────────────────────

    @Test
    fun `domain lists parse from the forms a model writes`() {
        assertEquals(listOf("a.com", "b.org"), WebSearchPolicy.parseDomainList("a.com, https://www.b.org/x"))
        assertEquals(listOf("a.com", "b.org"), WebSearchPolicy.parseDomainList("""["a.com","b.org"]"""))
        assertEquals(listOf("a.com"), WebSearchPolicy.parseDomainList("a.com A.com *.a.com"))
        assertEquals(emptyList<String>(), WebSearchPolicy.parseDomainList(null))
        assertEquals(emptyList<String>(), WebSearchPolicy.parseDomainList("  "))
    }

    @Test
    fun `domain lists are bounded`() {
        val many = (1..30).joinToString(",") { "d$it.com" }
        assertEquals(WebSearchPolicy.MAX_DOMAIN_FILTERS, WebSearchPolicy.parseDomainList(many).size)
    }

    @Test
    fun `include and exclude apply to subdomains and exclude wins`() {
        assertTrue(WebSearchPolicy.allowedByDomains("https://docs.a.com/x", listOf("a.com"), emptyList()))
        assertFalse(WebSearchPolicy.allowedByDomains("https://b.com/x", listOf("a.com"), emptyList()))
        assertFalse(WebSearchPolicy.allowedByDomains("https://ads.a.com/x", listOf("a.com"), listOf("ads.a.com")))
        assertTrue(WebSearchPolicy.allowedByDomains("https://b.com/x", emptyList(), listOf("a.com")))
        assertTrue(WebSearchPolicy.allowedByDomains("anything", emptyList(), emptyList()))
    }

    // ── per-domain cap ──────────────────────────────────────────────────────

    @Test
    fun `no cap just takes the limit in order`() {
        val rows = listOf(r("https://a.com/1"), r("https://a.com/2"), r("https://b.com/1"))
        assertEquals(rows.take(2), WebSearchPolicy.capPerDomain(rows, 0, 2))
    }

    @Test
    fun `the cap diversifies a full page`() {
        val rows = listOf(
            r("https://a.com/1"), r("https://a.com/2"), r("https://www.a.com/3"),
            r("https://b.com/1"), r("https://c.com/1"),
        )
        val out = WebSearchPolicy.capPerDomain(rows, 1, 3)
        assertEquals(listOf("https://a.com/1", "https://b.com/1", "https://c.com/1"), out.map { it.url })
    }

    @Test
    fun `the cap never returns fewer rows than were found`() {
        val rows = listOf(r("https://a.com/1"), r("https://a.com/2"), r("https://a.com/3"), r("https://b.com/1"))
        val out = WebSearchPolicy.capPerDomain(rows, 1, 4)
        assertEquals(
            listOf("https://a.com/1", "https://b.com/1", "https://a.com/2", "https://a.com/3"),
            out.map { it.url },
        )
    }

    @Test
    fun `refine dedupes filters then caps`() {
        val rows = listOf(
            r("https://a.com/1"), r("https://a.com/1/"), r("https://spam.com/1"),
            r("https://a.com/2"), r("https://b.com/1"),
        )
        val out = WebSearchPolicy.refine(rows, emptyList(), listOf("spam.com"), 1, 2)
        assertEquals(listOf("https://a.com/1", "https://b.com/1"), out.map { it.url })
    }

    @Test
    fun `a plain search asks the engine for exactly what it always did`() {
        assertEquals(5, WebSearchPolicy.requestCount(5, filtering = false))
        assertEquals(10, WebSearchPolicy.requestCount(5, filtering = true))
        assertEquals(16, WebSearchPolicy.requestCount(8, filtering = true))
    }

    // ── recency ─────────────────────────────────────────────────────────────

    @Test
    fun `recency normalises and a typo means no filter`() {
        assertEquals("week", WebSearchPolicy.normalizeRecency(" Week "))
        assertEquals("day", WebSearchPolicy.normalizeRecency("24h"))
        assertNull(WebSearchPolicy.normalizeRecency("fortnight"))
        assertNull(WebSearchPolicy.normalizeRecency(null))
    }

    @Test
    fun `each engine gets its own spelling and unsupported engines get none`() {
        assertEquals("w", WebSearchPolicy.recencyToken("ddg", "week"))
        assertEquals("pm", WebSearchPolicy.recencyToken("brave", "month"))
        assertEquals("year", WebSearchPolicy.recencyToken("tavily", "year"))
        assertEquals("day", WebSearchPolicy.recencyToken("searxng", "day"))
        assertEquals("qdr:d", WebSearchPolicy.recencyToken("serper", "day"))
        assertEquals("Week", WebSearchPolicy.recencyToken("bing", "week"))
        assertNull(WebSearchPolicy.recencyToken("bing", "year"))
        assertNull(WebSearchPolicy.recencyToken("bing_local", "week"))
        assertNull(WebSearchPolicy.recencyToken("jina", "week"))
        assertNull(WebSearchPolicy.recencyToken("ddg", null))
    }

    @Test
    fun `the recency start date is computed from the injected clock`() {
        // 2026-11-24T02:00:00Z
        val now = 1_795_485_600_000L
        assertEquals("2026-11-23T00:00:00.000Z", WebSearchPolicy.recencyStartIso("day", now))
        assertEquals("2026-11-17T00:00:00.000Z", WebSearchPolicy.recencyStartIso("week", now))
        assertEquals("2026-10-25T00:00:00.000Z", WebSearchPolicy.recencyStartIso("month", now))
        assertEquals("2025-11-24T00:00:00.000Z", WebSearchPolicy.recencyStartIso("year", now))
        // Across a leap day: 2024-03-01T00:00:00Z minus one day.
        assertEquals("2024-02-29T00:00:00.000Z", WebSearchPolicy.recencyStartIso("day", 1_709_251_200_000L))
    }

    // ── query operators ─────────────────────────────────────────────────────

    @Test
    fun `operator engines get site filters and others get the plain query`() {
        assertEquals("kotlin flow site:kotlinlang.org", WebSearchPolicy.queryWithDomains("ddg", "kotlin flow", listOf("kotlinlang.org"), emptyList()))
        assertEquals(
            "kotlin flow (site:a.com OR site:b.com) -site:c.com",
            WebSearchPolicy.queryWithDomains("brave", "kotlin flow", listOf("a.com", "b.com"), listOf("c.com")),
        )
        assertEquals("kotlin flow", WebSearchPolicy.queryWithDomains("jina", "kotlin flow", listOf("a.com"), emptyList()))
        assertEquals("kotlin flow", WebSearchPolicy.queryWithDomains("tavily", "kotlin flow", listOf("a.com"), emptyList()))
        assertEquals("kotlin flow", WebSearchPolicy.queryWithDomains("ddg", "kotlin flow", emptyList(), emptyList()))
    }

    @Test
    fun `a query that already scopes itself is left alone`() {
        val q = "flow site:github.com"
        assertEquals(q, WebSearchPolicy.queryWithDomains("ddg", q, listOf("a.com"), emptyList()))
    }

    @Test
    fun `only tavily and exa take native domain fields`() {
        assertTrue(WebSearchPolicy.hasNativeDomainFilter("tavily"))
        assertTrue(WebSearchPolicy.hasNativeDomainFilter("exa"))
        assertFalse(WebSearchPolicy.hasNativeDomainFilter("ddg"))
    }

    // ── language ────────────────────────────────────────────────────────────

    @Test
    fun `the query script decides before the device language`() {
        assertEquals("ja", WebSearchPolicy.searchLanguage("東京の天気は", "en-US"))
        assertEquals("ko", WebSearchPolicy.searchLanguage("서울 날씨", "en-US"))
        assertEquals("zh-Hans", WebSearchPolicy.searchLanguage("安卓 发布", "en-US"))
        assertEquals("zh-Hant", WebSearchPolicy.searchLanguage("安卓 發布", "zh-TW"))
    }

    @Test
    fun `a latin query follows the device and defaults to english`() {
        assertEquals("vi", WebSearchPolicy.searchLanguage("thời tiết hôm nay", "vi-VN"))
        assertEquals("en", WebSearchPolicy.searchLanguage("kotlin flow", null))
        assertEquals("en", WebSearchPolicy.searchLanguage("kotlin flow", ""))
        assertEquals("zh-Hans", WebSearchPolicy.searchLanguage("kotlin flow", "zh_CN"))
        assertEquals("en", WebSearchPolicy.searchLanguage("kotlin flow", "??"))
    }

    @Test
    fun `accept language always keeps english acceptable`() {
        assertEquals("en-US,en;q=0.9", WebSearchPolicy.acceptLanguage("en"))
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", WebSearchPolicy.acceptLanguage("zh-Hans"))
        assertEquals("zh-TW,zh;q=0.9,en;q=0.8", WebSearchPolicy.acceptLanguage("zh-Hant"))
        assertEquals("vi,en;q=0.8", WebSearchPolicy.acceptLanguage("vi"))
    }

    // ── engine order ────────────────────────────────────────────────────────

    @Test
    fun `fallback off means only the chosen engine`() {
        assertEquals(listOf("tavily"), WebSearchPolicy.engineOrder("tavily", true, listOf("tavily", "brave"), allowFallback = false))
    }

    @Test
    fun `a keyed choice falls back to other keyed engines then the keyless chain`() {
        assertEquals(
            listOf("tavily", "brave", "exa", "ddg", "bing_local"),
            WebSearchPolicy.engineOrder("tavily", true, listOf("tavily", "brave", "exa"), allowFallback = true),
        )
    }

    @Test
    fun `a keyless choice never spends keyed quota`() {
        assertEquals(
            listOf("ddg", "bing_local"),
            WebSearchPolicy.engineOrder("ddg", false, listOf("tavily", "brave"), allowFallback = true),
        )
        assertEquals(
            listOf("searxng", "ddg", "bing_local"),
            WebSearchPolicy.engineOrder("searxng", false, listOf("tavily"), allowFallback = true),
        )
        assertEquals(
            listOf("bing_local", "ddg"),
            WebSearchPolicy.engineOrder("bing_local", false, emptyList(), allowFallback = true),
        )
    }

    @Test
    fun `cooling engines are skipped but never the chosen one`() {
        assertEquals(
            listOf("tavily", "exa", "bing_local"),
            WebSearchPolicy.engineOrder("tavily", true, listOf("tavily", "brave", "exa"), true, coolingDown = setOf("tavily", "brave", "ddg")),
        )
    }

    @Test
    fun `when every fallback is cooling they are tried anyway`() {
        assertEquals(
            listOf("ddg", "bing_local"),
            WebSearchPolicy.engineOrder("ddg", false, emptyList(), true, coolingDown = setOf("bing_local")),
        )
    }

    // ── cooldown ────────────────────────────────────────────────────────────

    @Test
    fun `an engine rests after repeated failures and a success clears it`() {
        var now = 1_000L
        val cd = EngineCooldown(baseMs = 60_000L, maxMs = 600_000L, threshold = 2, clock = { now })
        cd.recordFailure("ddg")
        assertFalse(cd.isCooling("ddg"))
        cd.recordFailure("ddg")
        assertTrue(cd.isCooling("ddg"))
        assertEquals(setOf("ddg"), cd.cooling())
        now += 59_999L
        assertTrue(cd.isCooling("ddg"))
        now += 1L
        assertFalse(cd.isCooling("ddg"))
        cd.recordFailure("ddg")
        now += 119_999L
        assertTrue(cd.isCooling("ddg"))
        now += 1L
        assertFalse(cd.isCooling("ddg"))
        cd.recordFailure("ddg")
        cd.recordSuccess("ddg")
        assertFalse(cd.isCooling("ddg"))
        assertTrue(cd.cooling().isEmpty())
    }

    @Test
    fun `the rest is capped`() {
        var now = 0L
        val cd = EngineCooldown(baseMs = 60_000L, maxMs = 600_000L, threshold = 2, clock = { now })
        for (i in 0 until 40) cd.recordFailure("x")
        now += 600_000L
        assertFalse(cd.isCooling("x"))
    }
}
