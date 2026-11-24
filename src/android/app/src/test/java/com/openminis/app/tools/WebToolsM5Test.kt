package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-web-m5] The pure halves added to web_fetch and web_search. */
class WebToolsM5Test {

    // ── web_fetch ───────────────────────────────────────────────────────────

    @Test
    fun `new fetch parameters are optional`() {
        val def = WebFetchTool.definition()
        assertTrue(def.parameters.containsKey("offset"))
        assertTrue(def.parameters.containsKey("extract"))
        assertEquals(listOf("tool_title", "url"), def.required)
    }

    @Test
    fun `extract normalises to auto main or full`() {
        assertEquals("auto", WebFetchTool.normalizeExtract(""))
        assertEquals("auto", WebFetchTool.normalizeExtract("whatever"))
        assertEquals("main", WebFetchTool.normalizeExtract(" Main "))
        assertEquals("full", WebFetchTool.normalizeExtract("FULL"))
    }

    private val page: String = "<html><head><title>T</title></head><body><nav><a href=\"/n\">NavItem</a></nav>" +
        "<article><h1>Head</h1><p>" + "Body words here. ".repeat(40) + "</p></article><footer>FootItem</footer></body></html>"

    @Test
    fun `text with auto extraction is the unchanged legacy conversion`() {
        val out = WebFetchTool.convertHtml(page, "https://example.com/", "text", "auto")
        assertEquals(WebFetchTool.htmlToText(page), out.first)
        assertEquals("", out.second)
        assertTrue(out.first.contains("NavItem"))
    }

    @Test
    fun `markdown defaults to main content and says so`() {
        val out = WebFetchTool.convertHtml(page, "https://example.com/", "markdown", "auto")
        assertTrue(out.first.startsWith("# Head"))
        assertFalse(out.first.contains("NavItem"))
        assertFalse(out.first.contains("FootItem"))
        assertTrue(out.second.contains("extract=full"))
    }

    @Test
    fun `extract full keeps the furniture and extract main applies to text too`() {
        val full = WebFetchTool.convertHtml(page, "https://example.com/", "markdown", "full")
        assertTrue(full.first.contains("[NavItem](https://example.com/n)"))
        assertTrue(full.first.contains("FootItem"))
        assertEquals("", full.second)
        val mainText = WebFetchTool.convertHtml(page, "https://example.com/", "text", "main")
        assertFalse(mainText.first.contains("NavItem"))
        assertFalse(mainText.first.contains("# "))
        assertTrue(mainText.first.contains("Body words here."))
    }

    @Test
    fun `fragments are dropped before the guard sees the url`() {
        assertEquals("https://example.com/a?b=1", WebFetchTool.stripFragment("https://example.com/a?b=1#section-2"))
        assertEquals("https://example.com/a", WebFetchTool.stripFragment("https://example.com/a"))
        assertNull(FetchUrlGuard.blockedReason(WebFetchTool.stripFragment("https://example.com/a#x")))
    }

    @Test
    fun `the cache key separates what changes the content and hides header values`() {
        val base = WebFetchTool.cacheKey("https://example.com/a", "markdown", "auto", emptyMap(), false)
        assertEquals(base, WebFetchTool.cacheKey("https://example.com/a", "markdown", "auto", emptyMap(), false))
        assertNotEquals(base, WebFetchTool.cacheKey("https://example.com/a", "text", "auto", emptyMap(), false))
        assertNotEquals(base, WebFetchTool.cacheKey("https://example.com/a", "markdown", "full", emptyMap(), false))
        assertNotEquals(base, WebFetchTool.cacheKey("https://example.com/b", "markdown", "auto", emptyMap(), false))
        assertNotEquals(base, WebFetchTool.cacheKey("https://example.com/a", "markdown", "auto", emptyMap(), true))
        val withToken = WebFetchTool.cacheKey("https://example.com/a", "markdown", "auto", mapOf("Authorization" to "Bearer super-secret-token"), false)
        assertNotEquals(base, withToken)
        assertFalse(withToken.contains("super-secret-token"))
        assertNotEquals(
            withToken,
            WebFetchTool.cacheKey("https://example.com/a", "markdown", "auto", mapOf("Authorization" to "Bearer other"), false),
        )
    }

    @Test
    fun `binary content types are recognised and text ones are not`() {
        for (binary in listOf("application/pdf", "image/png", "application/zip", "video/mp4", "audio/mpeg", "font/woff2",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Application/PDF; qs=1")) {
            assertTrue(binary, WebFetchTool.isBinaryContentType(binary))
        }
        for (text in listOf("", "text/html; charset=utf-8", "application/json", "text/plain", "image/svg+xml",
            "application/vnd.api+json", "application/atom+xml", "application/xml", "application/javascript", "application/octet-stream")) {
            assertFalse(text, WebFetchTool.isBinaryContentType(text))
        }
    }

    @Test
    fun `undeclared binary is caught by its bytes`() {
        assertTrue(WebFetchTool.looksBinary(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x00, 0x01)))
        assertFalse(WebFetchTool.looksBinary("plain text é".toByteArray(Charsets.UTF_8)))
        assertFalse(WebFetchTool.looksBinary(ByteArray(0)))
    }

    @Test
    fun `the binary refusal names type size and a way forward`() {
        val msg = WebFetchTool.binaryMessage("https://example.com/a.pdf", "application/pdf; x=1", 123456L)
        assertTrue(msg.contains("application/pdf (123456 bytes)"))
        assertTrue(msg.contains("shell_execute"))
        assertTrue(WebFetchTool.binaryMessage("https://example.com/a.pdf", "application/pdf", null).contains("size unknown"))
    }

    @Test
    fun `charset comes from the header then the meta tag then utf8`() {
        assertEquals("windows-1252", WebFetchTool.detectCharset("text/html; charset=windows-1252", ByteArray(0)).name())
        assertEquals("Shift_JIS", WebFetchTool.detectCharset("text/html", "<html><head><meta charset=\"shift_jis\">".toByteArray()).name())
        assertEquals(
            "GBK",
            WebFetchTool.detectCharset("", "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gbk\">".toByteArray()).name(),
        )
        assertEquals(Charsets.UTF_8, WebFetchTool.detectCharset("text/html", "<html>".toByteArray()))
        assertEquals(Charsets.UTF_8, WebFetchTool.detectCharset("text/html; charset=no-such-charset-x", ByteArray(0)))
        val latin = "caf\u00e9".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café", String(latin, WebFetchTool.detectCharset("text/plain; charset=ISO-8859-1", latin)))
    }

    @Test
    fun `failures say which thing went wrong`() {
        assertTrue(WebFetchTool.describeFailure("https://e.com", java.net.SocketTimeoutException("timeout"), 30).contains("timed out after 30s"))
        assertTrue(WebFetchTool.describeFailure("https://e.com", java.net.UnknownHostException("e.com"), 30).contains("host not found"))
        assertTrue(
            WebFetchTool.describeFailure("https://e.com", java.io.IOException("Blocked request hop: private or loopback address"), 30)
                .contains("blocked by the URL guard"),
        )
        assertTrue(WebFetchTool.guardMessage("http://10.0.0.1/", "private or loopback address").startsWith("Error: blocked by the URL guard"))
    }

    // ── the hop guard ───────────────────────────────────────────────────────

    @Test
    fun `every hop is checked before it is requested`() {
        assertNull(FetchUrlGuard.hopBlockedReason("https://example.com/a", 443))
        assertNull(FetchUrlGuard.hopBlockedReason("http://example.com/a#frag", 80))
        // The cases a custom Dns never sees: IP-literal redirect targets.
        assertNotNull(FetchUrlGuard.hopBlockedReason("http://169.254.169.254/latest/meta-data/", 80))
        assertNotNull(FetchUrlGuard.hopBlockedReason("http://10.0.0.1/admin", 80))
        assertNotNull(FetchUrlGuard.hopBlockedReason("http://127.0.0.1/", 80))
        assertNotNull(FetchUrlGuard.hopBlockedReason("http://[::1]/", 80))
        assertNotNull(FetchUrlGuard.hopBlockedReason("http://localhost/", 80))
        assertNotNull(FetchUrlGuard.hopBlockedReason("http://user:pw@example.com/", 80))
        assertEquals("non-standard port 8080", FetchUrlGuard.hopBlockedReason("http://example.com:8080/", 8080))
    }

    @Test
    fun `the fetch client installs the hop guard and still the dns guard`() {
        val src = ProductionSources.read("tools/WebFetchTool.kt")
        assertTrue(src.contains(".dns(FetchUrlGuard.publicInternetDns())"))
        assertTrue(src.contains(".addNetworkInterceptor(FetchUrlGuard.hopGuard())"))
        // fetch_top goes through this same client: the search tool has no reader endpoint of its own.
        val search = ProductionSources.read("tools/WebSearchTool.kt")
        assertTrue(search.contains("WebFetchTool.excerpt("))
        assertFalse(search.contains("r.jina.ai"))
    }

    // ── web_search ──────────────────────────────────────────────────────────

    @Test
    fun `new search parameters are optional`() {
        val def = WebSearchTool.definition()
        for (name in listOf("include_domains", "exclude_domains", "recency", "max_per_domain", "fetch_top")) {
            assertTrue(name, def.parameters.containsKey(name))
        }
        assertEquals(listOf("tool_title", "query"), def.required)
        assertEquals(listOf("day", "week", "month", "year"), def.parameters["recency"]?.enumValues)
    }

    @Test
    fun `bing click trackers are unwrapped when they carry a target`() {
        val target = "https://kotlinlang.org/docs/flow.html"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        assertEquals(target, WebSearchTool.decodeBingTracker("https://www.bing.com/ck/a?!&&p=abc&u=a1$encoded&ntb=1"))
        assertNull(WebSearchTool.decodeBingTracker("https://www.bing.com/ck/a?u=abc"))
        val notHttp = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("javascript:alert(1)".toByteArray())
        assertNull(WebSearchTool.decodeBingTracker("https://www.bing.com/ck/a?u=a1$notHttp"))
    }

    @Test
    fun `bing html results carry their caption and unwrapped links`() {
        val target = "https://kotlinlang.org/docs/flow.html"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        val html = """
            <ol id="b_results">
            <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?p=1&amp;u=a1$encoded&amp;ntb=1">Flow | <strong>Kotlin</strong></a></h2>
              <div class="b_caption"><p>Asynchronous <strong>Flow</strong> documentation.</p></div></li>
            <li class="b_algo"><h2><a href="https://example.org/second">Second</a></h2></li>
            <li class="b_algo"><h2><a href="https://example.net/third">Third</a></h2><p>Third caption</p></li>
            </ol>
        """.trimIndent()
        val out = WebSearchTool.parseBingHtml(html, 8)
        assertEquals(listOf(target, "https://example.org/second", "https://example.net/third"), out.map { it.url })
        assertEquals("Asynchronous Flow documentation.", out[0].snippet)
        // A hit without a caption must not borrow the next hit's.
        assertEquals("", out[1].snippet)
        assertEquals("Third caption", out[2].snippet)
    }

    @Test
    fun `wikipedia edition follows the search language`() {
        assertEquals("en.wikipedia.org", WebSearchTool.wikipediaHost("en"))
        assertEquals("zh.wikipedia.org", WebSearchTool.wikipediaHost("zh-Hans"))
        assertEquals("vi.wikipedia.org", WebSearchTool.wikipediaHost("vi"))
        assertEquals("ja.wikipedia.org", WebSearchTool.wikipediaHost("ja"))
    }

    @Test
    fun `the search cache key covers the query and every option`() {
        val plain = WebSearchTool.SearchOptions()
        val base = WebSearchTool.searchCacheKey("ddg", true, 5, 0, plain, "Kotlin  Flow")
        assertEquals(base, WebSearchTool.searchCacheKey("ddg", true, 5, 0, plain, " kotlin flow "))
        assertNotEquals(base, WebSearchTool.searchCacheKey("brave", true, 5, 0, plain, "kotlin flow"))
        assertNotEquals(base, WebSearchTool.searchCacheKey("ddg", false, 5, 0, plain, "kotlin flow"))
        assertNotEquals(base, WebSearchTool.searchCacheKey("ddg", true, 8, 0, plain, "kotlin flow"))
        assertNotEquals(base, WebSearchTool.searchCacheKey("ddg", true, 5, 1, plain, "kotlin flow"))
        assertNotEquals(base, WebSearchTool.searchCacheKey("ddg", true, 5, 0, plain.copy(recency = "week"), "kotlin flow"))
        assertNotEquals(base, WebSearchTool.searchCacheKey("ddg", true, 5, 0, plain.copy(include = listOf("a.com")), "kotlin flow"))
        assertNotEquals(base, WebSearchTool.searchCacheKey("ddg", true, 5, 0, plain.copy(exclude = listOf("a.com")), "kotlin flow"))
        assertEquals(
            WebSearchTool.searchCacheKey("ddg", true, 5, 0, plain.copy(include = listOf("a.com", "b.com")), "q"),
            WebSearchTool.searchCacheKey("ddg", true, 5, 0, plain.copy(include = listOf("b.com", "a.com")), "q"),
        )
    }

    @Test
    fun `snippets are bounded to one line`() {
        assertEquals("a b c", WebSearchTool.clip(" a\n b\t c ", 10))
        val cut = WebSearchTool.clip("x".repeat(50), 10)
        assertEquals(11, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun `an empty query is still refused before any network`() {
        val r = WebSearchTool.execute("""{"tool_title":"t","query":"  ","fetch_top":3}""")
        assertFalse(r.success)
    }
}
