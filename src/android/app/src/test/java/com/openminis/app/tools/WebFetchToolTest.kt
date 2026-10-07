package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import java.net.InetAddress

/**
 * [T-android-web-fetch] The fetch tool's pure half.
 *
 * The point of these cases is the seam the tool exists for: whatever the model
 * writes in `headers`/`format`, and whatever the page contains, must land in a
 * predictable request and a readable result. The SSRF guard is pinned here too —
 * it is the only thing standing between "read this page" and "read the cloud
 * metadata endpoint", and the caller is a model reading untrusted text.
 */
class WebFetchToolTest {

    // ── headers ─────────────────────────────────────────────────────────────

    @Test
    fun `headers parse from a JSON object`() {
        val headers = WebFetchTool.parseHeaders("""{"X-Api-Key":"secret","Accept":"application/json"}""")
        assertEquals("secret", headers["X-Api-Key"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test
    fun `headers parse from Key colon value lines`() {
        val headers = WebFetchTool.parseHeaders(
            """
            Authorization: Bearer abc
            Accept-Language: vi,en;q=0.8
            """.trimIndent(),
        )
        assertEquals("Bearer abc", headers["Authorization"])
        assertEquals("vi,en;q=0.8", headers["Accept-Language"])
    }

    @Test
    fun `headers the client owns are dropped, not forwarded`() {
        // Host/Content-Length are the client's: letting the model set them
        // produces a malformed request, and a name with a space or colon is
        // header splitting.
        val headers = WebFetchTool.parseHeaders(
            """
            Host: evil.example
            Content-Length: 0
            Bad Name: x
            Good: y
            """.trimIndent(),
        )
        assertEquals(setOf("Good"), headers.keys)
        assertTrue(WebFetchTool.parseHeaders("").isEmpty())
        assertTrue(WebFetchTool.parseHeaders("{not json").isEmpty())
    }

    // ── extraction ──────────────────────────────────────────────────────────

    @Test
    fun `htmlToText drops scripts and keeps block structure`() {
        val html = """
            <html><head><title>T</title><style>p{color:red}</style></head>
            <body><script>var x = 1;</script>
            <h1>Title</h1><p>First   paragraph.</p><p>Second &amp; third.</p>
            <ul><li>one</li><li>two</li></ul>
            <noscript>enable js</noscript>
            </body></html>
        """.trimIndent()
        val text = WebFetchTool.htmlToText(html)
        assertFalse(text.contains("var x"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("enable js"))
        // Paragraphs and list items are separate lines, not one run-on line.
        assertTrue(text, text.contains("Title\nFirst paragraph."))
        assertTrue(text, text.contains("First paragraph.\nSecond & third."))
        assertTrue(text, text.contains("- one\n- two"))
    }

    @Test
    fun `htmlToMarkdown keeps headings links lists and code`() {
        val html = """
            <h2>Release notes</h2>
            <p>See <a href="/docs/2.0">the docs</a> and <a href="https://x.example/a">x</a>.</p>
            <ul><li>Fixed <strong>timeouts</strong></li></ul>
            <pre>pip install foo</pre>
        """.trimIndent()
        val md = WebFetchTool.htmlToMarkdown(html, baseUrl = "https://api.example/v2/spec")
        assertTrue(md, md.contains("## Release notes"))
        // Relative links are resolved against the page, so the model can follow them.
        assertTrue(md, md.contains("[the docs](https://api.example/docs/2.0)"))
        assertTrue(md, md.contains("[x](https://x.example/a)"))
        assertTrue(md, md.contains("- Fixed **timeouts**"))
        assertTrue(md, md.contains("```\npip install foo\n```"))
    }

    @Test
    fun `absoluteUrl leaves non-navigable hrefs alone`() {
        assertEquals("https://a.example/x", WebFetchTool.absoluteUrl("/x", "https://a.example/page"))
        assertEquals("#frag", WebFetchTool.absoluteUrl("#frag", "https://a.example/"))
        assertEquals("data:text/plain,hi", WebFetchTool.absoluteUrl("data:text/plain,hi", "https://a.example/"))
        assertEquals("javascript:void(0)", WebFetchTool.absoluteUrl("javascript:void(0)", "https://a.example/"))
        // No base URL: the href is all we have, so it is passed through unchanged.
        assertEquals("/x", WebFetchTool.absoluteUrl("/x", null))
    }

    // ── formatting ──────────────────────────────────────────────────────────

    @Test
    fun `prettyJsonIfPossible indents objects and arrays, rejects the rest`() {
        // Own printer, so the shape is ours to promise: 2-space indent, one field
        // per line, nested values indented under their key.
        assertEquals(
            """
            {
              "a": 1
            }
            """.trimIndent(),
            WebFetchTool.prettyJsonIfPossible("""{"a":1}"""),
        )
        assertEquals(
            """
            {
              "a": {
                "b": 1
              }
            }
            """.trimIndent(),
            WebFetchTool.prettyJsonIfPossible("""{"a":{"b":1}}"""),
        )
        assertEquals(
            """
            [
              1,
              2
            ]
            """.trimIndent(),
            WebFetchTool.prettyJsonIfPossible("""[1,2]"""),
        )
        // Values keep their JSON types; empty containers stay on one line.
        assertEquals("{}", WebFetchTool.prettyJsonIfPossible("{}"))
        assertEquals("[\n  true,\n  null,\n  \"x\"\n]", WebFetchTool.prettyJsonIfPossible("""[true,null,"x"]"""))
        assertNull(WebFetchTool.prettyJsonIfPossible("<html></html>"))
        assertNull(WebFetchTool.prettyJsonIfPossible(""))
        assertNull(WebFetchTool.prettyJsonIfPossible("""{"broken":"""))
    }

    @Test
    fun `truncation says how much was cut`() {
        assertEquals("short", WebFetchTool.truncateWithNote("short", 100))
        val cut = WebFetchTool.truncateWithNote("x".repeat(500), 100)
        assertTrue(cut, cut.startsWith("x".repeat(100)))
        assertTrue(cut, cut.contains("showing 100 of 500 characters"))
    }

    @Test
    fun `definition requires a url and keeps the tool name`() {
        val definition = WebFetchTool.definition()
        assertEquals("web_fetch", definition.name)
        assertTrue(definition.required.contains("url"))
        assertTrue(definition.parameters.containsKey("headers"))
        assertTrue(definition.parameters.containsKey("render"))
    }

    // ── SSRF guard ──────────────────────────────────────────────────────────

    @Test
    fun `blockedReason refuses local, private and metadata hosts`() {
        for (url in listOf(
            "http://localhost/admin",
            "http://127.0.0.1:8080/",
            "http://10.1.2.3/",
            "http://172.16.5.5/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://router.local/",
            "http://svc.internal/",
            "file:///etc/passwd",
            "ftp://example.com/x",
            "https://user:pass@example.com/",
            "https://example.com/page#frag",
            "https://[::1]/",
        )) {
            assertTrue("should block: $url", FetchUrlGuard.blockedReason(url) != null)
        }
    }

    @Test
    fun `blockedReason allows ordinary public URLs`() {
        assertNull(FetchUrlGuard.blockedReason("https://example.com/docs"))
        assertNull(FetchUrlGuard.blockedReason("http://example.com:8080/x?y=1"))
        assertNull(FetchUrlGuard.blockedReason("https://en.wikipedia.org/wiki/Alpine_Linux"))
        // A private-looking label is fine as long as the host is public.
        assertEquals("url is required", FetchUrlGuard.blockedReason("  "))
    }

    @Test
    fun `unsafe addresses cover loopback site-local link-local and public`() {
        assertTrue(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("192.168.0.1")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("::1")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("fd00::1")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("fe80::1")))
        // 1.1.1.1 and 2606:4700:: are globally routable: the guard must not break
        // the ordinary case it exists to enable.
        assertFalse(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(FetchUrlGuard.isUnsafeAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
    // ── response bodies ─────────────────────────────────────────────────────
    //
    // The regression these pin: `readByteArray(n)` demands EXACTLY n bytes, so a body
    // shorter than the ceiling threw `EOFException("End of input")`. OkHttp reports
    // contentLength() == -1 for anything gzip'd (which is everything, since it sends
    // Accept-Encoding) or chunked, so the tool asked for the full 2MB and died on
    // every plain fetch while `render: true` and curl kept working — the shape of the
    // report that led here.

    private fun unknownLengthBody(text: String): ResponseBody = object : ResponseBody() {
        override fun contentType() = "text/html".toMediaType()
        // -1 = "length not known up front": gzip-decoded, chunked, or a stream.
        override fun contentLength(): Long = -1L
        override fun source() = Buffer().writeUtf8(text)
    }

    @Test
    fun `a short body with an unknown length is read to the end, not rejected`() {
        val bytes = HttpBodyReader.readCapped(unknownLengthBody("hello"), maxBytes = 2_000_000)
        assertEquals("hello", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `a body longer than the cap is cut at the cap`() {
        val body = "x".repeat(5_000).toResponseBody("text/plain".toMediaType())
        assertEquals(1_000, HttpBodyReader.readCapped(body, maxBytes = 1_000).size)
    }

    @Test
    fun `a known short length and an empty or absent body behave`() {
        val known = "abc".toResponseBody("text/plain".toMediaType())
        assertEquals(3, HttpBodyReader.readCapped(known, maxBytes = 2_000_000).size)
        assertEquals(0, HttpBodyReader.readCapped("".toResponseBody(null), maxBytes = 100).size)
        assertEquals(0, HttpBodyReader.readCapped(null, maxBytes = 100).size)
        assertEquals(0, HttpBodyReader.readCapped(unknownLengthBody("data"), maxBytes = 0).size)
    }

    @Test
    fun `a body split across reads is assembled in full`() {
        // 64KB chunking inside the reader: a body just over one chunk must not lose
        // its tail.
        val text = "y".repeat(70_000)
        val bytes = HttpBodyReader.readCapped(unknownLengthBody(text), maxBytes = 2_000_000)
        assertEquals(70_000, bytes.size)
        assertEquals(text, String(bytes, Charsets.UTF_8))
    }

    // ── entity decoding (shared with web_search) ────────────────────────────

    @Test
    fun `numeric and named entities decode, unknown ones are left alone`() {
        assertEquals("Hà Nội", HtmlText.decodeEntities("H&#224; N&#x1ED9;i"))
        assertEquals("Tiếng Việt", HtmlText.decodeEntities("Ti&#7871;ng Vi&#7879;t"))
        assertEquals("café — 5 × 3 €", HtmlText.decodeEntities("caf&eacute; &mdash; 5 &times; 3 &euro;"))
        assertEquals("a & b < c > d \" e ' f", HtmlText.decodeEntities("a &amp; b &lt; c &gt; d &quot; e &#39; f"))
        // Astral code point and the surrogate-pair form pages actually emit.
        assertEquals("\uD83D\uDE00", HtmlText.decodeEntities("&#x1F600;"))
        assertEquals("\uD83D\uDE00", HtmlText.decodeEntities("&#55357;&#56832;"))
        // A decoder that drops text is worse than one that leaves the reference.
        assertEquals("&#xZZ; &unknownentity; &#; &# ", HtmlText.decodeEntities("&#xZZ; &unknownentity; &#; &# "))
        assertEquals("plain text without entities", HtmlText.decodeEntities("plain text without entities"))
    }

}
