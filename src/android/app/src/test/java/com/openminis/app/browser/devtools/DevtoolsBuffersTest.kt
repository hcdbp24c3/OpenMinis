package com.openminis.app.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [M5-browser-devtools] Limits, console ring, network ring and the page event parser. */
class DevtoolsBuffersTest {

    // ── limits ──

    @Test
    fun `clip and capOutput bound text and say so`() {
        assertEquals("", DevtoolsLimits.clip(null, 10))
        assertEquals("abc", DevtoolsLimits.clip("abc", 10))
        val cut = DevtoolsLimits.clip("x".repeat(50), 10)
        assertEquals(10, cut.length)
        assertTrue(cut.endsWith("…"))
        val capped = DevtoolsLimits.capOutput("y".repeat(120), 100)
        assertTrue(capped.startsWith("y".repeat(100)))
        assertTrue(capped.contains("truncated: 20 more chars"))
        assertEquals("short", DevtoolsLimits.capOutput("short", 100))
    }

    @Test
    fun `timeout reads small values as seconds and clamps`() {
        assertEquals(10_000L, DevtoolsLimits.timeoutMs(null, 10_000L, 30_000L))
        assertEquals(10_000L, DevtoolsLimits.timeoutMs(0, 10_000L, 30_000L))
        assertEquals(5_000L, DevtoolsLimits.timeoutMs(5, 10_000L, 30_000L))
        assertEquals(5_000L, DevtoolsLimits.timeoutMs(5_000, 10_000L, 30_000L))
        assertEquals(30_000L, DevtoolsLimits.timeoutMs(120, 10_000L, 30_000L))
        assertEquals(30_000L, DevtoolsLimits.timeoutMs(999_999, 10_000L, 30_000L))
        assertEquals(DevtoolsLimits.WAIT_MIN_TIMEOUT_MS, DevtoolsLimits.timeoutMs(121, 10_000L, 30_000L))
    }

    @Test
    fun `refs are normalised and anything else is rejected`() {
        assertEquals("e12", DevtoolsLimits.normalizeRef("e12"))
        assertEquals("e12", DevtoolsLimits.normalizeRef("@e12"))
        assertEquals("e12", DevtoolsLimits.normalizeRef(" [ref=E12] "))
        assertEquals("e12", DevtoolsLimits.normalizeRef("ref=e12"))
        for (bad in listOf(null, "", "12", "e", "#e12", "e12'", "e1 e2", "e12);alert(1", "e123456789", "button")) {
            assertNull("should reject: $bad", DevtoolsLimits.normalizeRef(bad))
        }
    }

    // ── console ──

    @Test
    fun `console ring keeps the newest entries and a monotonic cursor`() {
        val log = ConsoleLog(capacity = 5)
        for (i in 1..12) log.add("log", "console", "m$i")
        assertEquals(5, log.size)
        assertEquals(12, log.cursor)
        val read = log.read(limit = 50)
        assertEquals(listOf("m8", "m9", "m10", "m11", "m12"), read.shown.map { it.message })
        assertEquals(listOf(8, 9, 10, 11, 12), read.shown.map { it.seq })
        // since-cursor: only what arrived after seq 10
        assertEquals(listOf("m11", "m12"), log.read(since = 10).shown.map { it.message })
        log.clear()
        assertEquals(0, log.size)
        assertEquals("cursor survives a clear so 'since' stays valid", 12, log.cursor)
        log.add("log", "console", "after")
        assertEquals(13, log.read().shown.single().seq)
    }

    @Test
    fun `console caps message and stack length`() {
        val log = ConsoleLog()
        log.add("error", "exception", "x".repeat(5_000), "https://a/b.js", 3, 9, "s".repeat(9_000))
        val e = log.read().shown.single()
        assertEquals(DevtoolsLimits.CONSOLE_MESSAGE_CHARS, e.message.length)
        assertEquals(DevtoolsLimits.CONSOLE_STACK_CHARS, e.stack.length)
    }

    @Test
    fun `console filters by minimum level and by text`() {
        val log = ConsoleLog()
        log.add("debug", "console", "d")
        log.add("log", "console", "l")
        log.add("info", "console", "i")
        log.add("warning", "console", "w token")
        log.add("error", "console", "e token")
        assertEquals(listOf("w token", "e token"), log.read(minLevel = "warn").shown.map { it.message })
        assertEquals(listOf("e token"), log.read(minLevel = "error").shown.map { it.message })
        assertEquals(5, log.read(minLevel = null).shown.size)
        assertEquals(2, log.read(filter = "TOKEN").shown.size)
        assertEquals("warn", ConsoleLog.normalizeLevel("WARNING"))
        assertEquals("log", ConsoleLog.normalizeLevel("nonsense"))
        // limit keeps the newest and reports how many matched
        val limited = log.read(limit = 2)
        assertEquals(listOf("w token", "e token"), limited.shown.map { it.message })
        assertEquals(5, limited.matched)
    }

    @Test
    fun `native uncaught message and injected exception merge into one entry with the stack`() {
        val log = ConsoleLog()
        // Native WebChromeClient report: no column, no stack.
        log.add("error", "console", "Uncaught TypeError: x is not a function", "https://a/app.js", 3)
        // Injected window 'error' listener: same failure, with column + stack.
        log.add("error", "exception", "Uncaught TypeError: x is not a function", "https://a/app.js", 3, 9, "TypeError: x is not a function\n    at foo (https://a/app.js:3:9)")
        val shown = log.read().shown
        assertEquals(1, shown.size)
        assertEquals("exception", shown[0].kind)
        assertEquals(9, shown[0].column)
        assertTrue(shown[0].stack.contains("at foo"))
        // An unrelated error is not swallowed.
        log.add("error", "console", "Uncaught ReferenceError: y is not defined", "https://a/app.js", 8)
        assertEquals(2, log.read().shown.size)
        // Two identical console.log lines are two entries (not twins).
        log.add("log", "console", "tick")
        log.add("log", "console", "tick")
        assertEquals(4, log.read().shown.size)
    }

    @Test
    fun `console report shows location stack and the cursor to continue from`() {
        val log = ConsoleLog()
        log.add("log", "nav", "navigated to https://a/")
        log.add("error", "exception", "Uncaught Error: boom", "https://a/app.js", 3, 9, "Error: boom\n    at foo (https://a/app.js:3:9)\n    at bar (https://a/app.js:7:1)")
        val text = ConsoleFormatter.format(log.read())
        assertTrue(text, text.contains("Uncaught Error: boom"))
        assertTrue(text, text.contains("https://a/app.js:3:9"))
        assertTrue(text, text.contains("at bar"))
        assertTrue(text, text.contains("since=2"))
        val empty = ConsoleFormatter.format(ConsoleLog().read())
        assertTrue(empty, empty.contains("no matching entries"))
    }

    // ── network ──

    @Test
    fun `network observers of one request merge into one entry`() {
        val log = NetworkLog()
        val url = "https://api.example.com/v1/items?page=2"
        log.record(NetEvent(source = "req", url = url, method = "GET", type = "xhr"))
        log.record(NetEvent(source = "js", url = url, method = "GET", type = "fetch", status = 200, mime = "application/json",
            durationMs = 120, responseHeaders = mapOf("content-type" to "application/json"), bodyPreview = "{\"ok\":true}"))
        log.record(NetEvent(source = "rt", url = url, type = "fetch", status = 200, durationMs = 118, transferSize = 512, encodedSize = 300, decodedSize = 900))
        assertEquals(1, log.size)
        val e = log.read().shown.single()
        assertEquals("GET", e.method)
        assertEquals(200, e.status)
        assertEquals("application/json", e.mime)
        assertEquals(512L, e.transferSize)
        assertEquals("{\"ok\":true}", e.bodyPreview)
        assertTrue(e.sources.containsAll(listOf("req", "js", "rt")))
        assertFalse(e.isProblem)
        // The same URL requested again later is a second entry, not a merge.
        log.record(NetEvent(source = "req", url = url, method = "GET", type = "xhr"))
        assertEquals(2, log.size)
    }

    @Test
    fun `network marks http errors and failures and can list only those`() {
        val log = NetworkLog()
        log.record(NetEvent(source = "req", url = "https://a/ok.js", method = "GET", type = "script"))
        log.record(NetEvent(source = "req", url = "https://a/missing.png", method = "GET", type = "image"))
        log.record(NetEvent(source = "err", url = "https://a/missing.png", status = 404, mime = "text/html"))
        log.record(NetEvent(source = "req", url = "https://b/down", method = "POST", type = "fetch"))
        log.record(NetEvent(source = "err", url = "https://b/down", failed = true, error = "net::ERR_CONNECTION_REFUSED"))
        assertEquals(3, log.size)
        val problems = log.read(problemsOnly = true).shown
        assertEquals(listOf("https://a/missing.png", "https://b/down"), problems.map { it.url })
        assertEquals(404, problems[0].status)
        assertTrue(problems[1].failed)
        assertEquals(listOf("https://b/down"), log.read(filter = "b/down").shown.map { it.url })
        val list = NetworkFormatter.formatList(log.read(), deepCapture = false)
        assertTrue(list, list.contains("404"))
        assertTrue(list, list.contains("ERR_CONNECTION_REFUSED"))
        assertTrue(list, list.contains("request_id"))
    }

    @Test
    fun `network ring is bounded and clips urls headers and bodies`() {
        val log = NetworkLog(capacity = 4)
        for (i in 1..10) log.record(NetEvent(source = "req", url = "https://a/r$i", method = "GET"))
        assertEquals(4, log.size)
        assertEquals(listOf("https://a/r7", "https://a/r8", "https://a/r9", "https://a/r10"), log.read().shown.map { it.url })
        assertNull(log.find(1))
        assertNotNull(log.find(10))

        val big = NetworkLog()
        val headers = (1..80).associate { "h$it" to "v".repeat(1_000) }
        val e = big.record(NetEvent(source = "js", url = "https://a/" + "p".repeat(5_000), method = "GET", status = 200,
            requestHeaders = headers, responseHeaders = headers, bodyPreview = "b".repeat(50_000)))
        assertTrue(e.url.length <= DevtoolsLimits.NETWORK_URL_CHARS)
        assertTrue(e.requestHeaders.size <= DevtoolsLimits.NETWORK_HEADERS)
        assertTrue(e.responseHeaders.values.all { it.length <= DevtoolsLimits.NETWORK_HEADER_VALUE_CHARS })
        assertTrue(e.bodyPreview.length <= DevtoolsLimits.NETWORK_BODY_PREVIEW_CHARS)
        assertTrue(e.bodyTruncated)
        val detail = NetworkFormatter.formatDetail(e)
        assertTrue(detail.length < 12_000)
    }

    @Test
    fun `network detail explains a missing body`() {
        val log = NetworkLog()
        val e = log.record(NetEvent(source = "rt", url = "https://a/app.js", type = "script", status = 200, durationMs = 40, transferSize = 1200))
        val detail = NetworkFormatter.formatDetail(e)
        assertTrue(detail, detail.contains("https://a/app.js"))
        assertTrue(detail, detail.lowercase().contains("body"))
    }

    @Test
    fun `resource type is guessed from the request when the page did not say`() {
        assertEquals("document", NetworkLog.guessType(true, "text/html", "https://a/"))
        assertEquals("script", NetworkLog.guessType(false, "*/*", "https://a/app.min.js?v=3"))
        assertEquals("css", NetworkLog.guessType(false, "text/css,*/*;q=0.1", "https://a/x"))
        assertEquals("img", NetworkLog.guessType(false, "image/avif,image/webp,*/*", "https://a/x"))
        assertEquals("font", NetworkLog.guessType(false, "*/*", "https://a/f.woff2"))
    }

    // ── event batches from the page ──

    @Test
    fun `page event batch is parsed into console and network events`() {
        val batch = JSONArray()
            .put(JSONObject().put("k", "c").put("lv", "error").put("kind", "rejection").put("m", "Unhandled promise rejection: Error: nope").put("st", "Error: nope\n    at x"))
            .put(JSONObject().put("k", "n").put("src", "fetch").put("u", "https://a/api").put("me", "post").put("s", 201).put("ty", "fetch")
                .put("d", 33).put("mt", "application/json").put("rq", JSONObject().put("x-t", "v")).put("rs", JSONObject().put("content-type", "application/json")).put("b", "{}"))
            .put(JSONObject().put("k", "n").put("src", "rt").put("u", "https://a/i.png").put("ty", "img").put("d", 12).put("ts", 0).put("es", 0))
            .put(JSONObject().put("k", "zzz"))
            .put("not an object")
        val parsed = DevtoolsEvents.parse(batch.toString())
        assertEquals(1, parsed.console.size)
        assertEquals("rejection", parsed.console[0].kind)
        assertEquals(2, parsed.network.size)
        val f = parsed.network[0]
        assertEquals("js", f.source)
        assertEquals(201, f.status)
        assertEquals("{}", f.bodyPreview)
        assertEquals("v", f.requestHeaders["x-t"])
        assertEquals("rt", parsed.network[1].source)
    }

    @Test
    fun `hostile or broken batches are ignored and oversized ones are bounded`() {
        assertTrue(DevtoolsEvents.parse(null).console.isEmpty())
        assertTrue(DevtoolsEvents.parse("").network.isEmpty())
        assertTrue(DevtoolsEvents.parse("{not json").console.isEmpty())
        assertTrue(DevtoolsEvents.parse("{\"k\":\"c\"}").console.isEmpty())
        val many = JSONArray()
        for (i in 1..5_000) many.put(JSONObject().put("k", "c").put("lv", "log").put("m", "m$i"))
        assertTrue(DevtoolsEvents.parse(many.toString()).console.size <= DevtoolsLimits.EVENT_BATCH_ITEMS)
        // A network event without a URL is dropped.
        val noUrl = JSONArray().put(JSONObject().put("k", "n").put("src", "fetch").put("s", 200))
        assertTrue(DevtoolsEvents.parse(noUrl.toString()).network.isEmpty())
    }
}
