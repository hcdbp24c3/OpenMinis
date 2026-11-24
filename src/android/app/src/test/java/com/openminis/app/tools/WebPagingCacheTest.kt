package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-web-m5] Window maths, the bounded TTL cache, and the retry rule. */
class WebPagingCacheTest {

    // ── paging ──────────────────────────────────────────────────────────────

    @Test
    fun `a short text is one window with no trailer`() {
        val w = WebPaging.window("hello", 0, 100)
        assertEquals("hello", w.content)
        assertFalse(w.hasMore)
        assertNull(w.nextOffset)
        assertEquals("", WebPaging.trailer(w))
    }

    @Test
    fun `windows concatenate back to the text exactly`() {
        val text = (1..400).joinToString("\n") { "line $it of the page 😀 with some words" }
        val sb = StringBuilder()
        var offset = 0
        var guard = 0
        while (true) {
            val w = WebPaging.window(text, offset, 257)
            assertTrue(w.content.length <= 257)
            assertTrue(w.content.isNotEmpty())
            sb.append(w.content)
            val next = w.nextOffset ?: break
            assertTrue(next > offset)
            offset = next
            guard++
            assertTrue(guard < 10_000)
        }
        assertEquals(text, sb.toString())
    }

    @Test
    fun `a window prefers to end on a line break near its end`() {
        val text = "a".repeat(90) + "\n" + "b".repeat(200)
        val w = WebPaging.window(text, 0, 100)
        assertEquals(91, w.end)
        assertTrue(w.content.endsWith("\n"))
    }

    @Test
    fun `a far away line break is not used`() {
        val text = "a".repeat(10) + "\n" + "b".repeat(200)
        assertEquals(100, WebPaging.window(text, 0, 100).end)
    }

    @Test
    fun `a surrogate pair is never split`() {
        val text = "ab" + "😀" + "cd"
        val w = WebPaging.window(text, 0, 3)
        assertEquals("ab", w.content)
        assertEquals(2, w.nextOffset)
        val second = WebPaging.window(text, 3, 10)
        assertEquals("😀cd", second.content)
        assertEquals(2, second.start)
    }

    @Test
    fun `a window of one char still advances past an emoji`() {
        val text = "😀😀"
        val w = WebPaging.window(text, 0, 1)
        assertTrue(w.end > 0)
    }

    @Test
    fun `offsets are clamped`() {
        assertEquals("", WebPaging.window("abc", 99, 10).content)
        assertEquals("abc", WebPaging.window("abc", -5, 10).content)
    }

    @Test
    fun `the trailer names the offset to continue from`() {
        val text = "x".repeat(1000)
        val first = WebPaging.window(text, 0, 300)
        assertTrue(WebPaging.trailer(first).contains("offset=300"))
        assertTrue(WebPaging.trailer(first).contains("characters 0–300 of 1000"))
        val last = WebPaging.window(text, 900, 300)
        assertTrue(WebPaging.trailer(last).contains("end of content"))
        assertFalse(WebPaging.trailer(last).contains("offset="))
    }

    @Test
    fun `an offset past the end is a clear error`() {
        assertNotNull(WebPaging.offsetError(500, 500))
        assertNull(WebPaging.offsetError(499, 500))
        assertNull(WebPaging.offsetError(0, 0))
    }

    @Test
    fun `the excerpt budget is shared with a floor and a ceiling`() {
        assertEquals(2000, WebPaging.shareBudget(6000, 3))
        assertEquals(4000, WebPaging.shareBudget(6000, 1))
        assertEquals(800, WebPaging.shareBudget(1000, 3))
        assertEquals(0, WebPaging.shareBudget(6000, 0))
    }

    // ── cache ───────────────────────────────────────────────────────────────

    private fun cache(now: () -> Long, entries: Int = 3, weight: Long = 100L, ttl: Long = 1000L): WebCache<String> =
        WebCache(entries, weight, ttl, { it.length.toLong() }, now)

    @Test
    fun `an entry is served until its ttl and reports its age`() {
        var now = 0L
        val c = cache({ now })
        c.put("k", "value")
        now = 400L
        assertEquals(400L, c.get("k")?.ageMs)
        assertEquals("value", c.get("k")?.value)
        now = 999L
        assertNotNull(c.get("k"))
        now = 1000L
        assertNull(c.get("k"))
        assertEquals(0, c.size())
        assertEquals(0L, c.totalWeight())
    }

    @Test
    fun `the least recently used entry goes first`() {
        var now = 0L
        val c = cache({ now })
        c.put("a", "1")
        c.put("b", "2")
        c.put("c", "3")
        assertNotNull(c.get("a"))
        c.put("d", "4")
        assertNull(c.get("b"))
        assertNotNull(c.get("a"))
        assertNotNull(c.get("c"))
        assertNotNull(c.get("d"))
        assertEquals(3, c.size())
    }

    @Test
    fun `the weight bound evicts and an oversized value is not stored`() {
        var now = 0L
        val c = cache({ now }, entries = 10, weight = 10L)
        c.put("a", "12345")
        c.put("b", "12345")
        assertEquals(10L, c.totalWeight())
        c.put("c", "123")
        assertNull(c.get("a"))
        assertEquals(8L, c.totalWeight())
        c.put("big", "x".repeat(11))
        assertNull(c.get("big"))
        assertEquals(8L, c.totalWeight())
    }

    @Test
    fun `replacing a key does not leak weight`() {
        var now = 0L
        val c = cache({ now })
        c.put("a", "12345")
        c.put("a", "12")
        assertEquals(2L, c.totalWeight())
        assertEquals(1, c.size())
        c.remove("a")
        assertEquals(0L, c.totalWeight())
    }

    @Test
    fun `expired entries are dropped before live ones`() {
        var now = 0L
        val c = cache({ now }, entries = 2)
        c.put("old", "1")
        now = 900L
        c.put("live", "2")
        now = 1100L
        c.put("new", "3")
        assertNotNull(c.get("live"))
        assertNotNull(c.get("new"))
        assertEquals(2, c.size())
    }

    @Test
    fun `a clock that goes backwards does not serve the entry`() {
        var now = 5000L
        val c = cache({ now })
        c.put("k", "v")
        now = 100L
        assertNull(c.get("k"))
    }

    // ── retry ───────────────────────────────────────────────────────────────

    @Test
    fun `only transient statuses and repeatable methods retry`() {
        assertTrue(HttpRetryPolicy.isRetryableStatus(429))
        assertTrue(HttpRetryPolicy.isRetryableStatus(503))
        assertFalse(HttpRetryPolicy.isRetryableStatus(500))
        assertFalse(HttpRetryPolicy.isRetryableStatus(404))
        assertFalse(HttpRetryPolicy.isRetryableStatus(200))
        assertTrue(HttpRetryPolicy.isRetryableMethod("get"))
        assertTrue(HttpRetryPolicy.isRetryableMethod("HEAD"))
        assertFalse(HttpRetryPolicy.isRetryableMethod("POST"))
    }

    @Test
    fun `retries are bounded and back off`() {
        assertEquals(600L, HttpRetryPolicy.delayMs(1, null))
        assertEquals(1800L, HttpRetryPolicy.delayMs(2, null))
        assertNull(HttpRetryPolicy.delayMs(3, null))
        assertNull(HttpRetryPolicy.delayMs(0, null))
    }

    @Test
    fun `retry after is honoured when short and refused when long`() {
        assertEquals(2000L, HttpRetryPolicy.delayMs(1, "2"))
        assertEquals(250L, HttpRetryPolicy.delayMs(1, "0"))
        assertEquals(5000L, HttpRetryPolicy.delayMs(1, "5"))
        assertNull(HttpRetryPolicy.delayMs(1, "6"))
        assertNull(HttpRetryPolicy.delayMs(1, "3600"))
        // An HTTP-date is not parsed: default backoff.
        assertEquals(600L, HttpRetryPolicy.delayMs(1, "Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `statuses are described in words`() {
        assertEquals("rate limited (HTTP 429, retry after 30s)", HttpRetryPolicy.describe(429, "30"))
        assertEquals("rate limited (HTTP 429)", HttpRetryPolicy.describe(429, null))
        assertTrue(HttpRetryPolicy.describe(401, null).contains("HTTP 401"))
        assertEquals("server error (HTTP 503)", HttpRetryPolicy.describe(503, null))
        assertEquals("HTTP 418", HttpRetryPolicy.describe(418, null))
    }
}
