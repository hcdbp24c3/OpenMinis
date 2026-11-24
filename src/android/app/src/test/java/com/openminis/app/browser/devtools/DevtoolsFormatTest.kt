package com.openminis.app.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [M5-browser-devtools] Text the model reads for snapshot / inspect / storage / performance / evaluate / wait. */
class DevtoolsFormatTest {

    private fun node(role: String, name: String, depth: Int, ref: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("ro", role).put("n", name).put("d", depth).put("r", ref).apply(extra)

    @Test
    fun `snapshot is one compact line per element with its ref`() {
        val json = JSONObject().put("url", "https://example.com/login").put("title", "Sign in").put("total", 4).put("interactive", true)
            .put("nodes", JSONArray()
                .put(node("form", "", 0, "e1"))
                .put(node("textbox", "Email", 1, "e2") { put("ty", "email"); put("v", "a@b.c"); put("s", JSONArray().put("required")) })
                .put(node("button", "Sign in", 1, "e3") { put("s", JSONArray().put("disabled")) })
                .put(node("link", "Forgot password", 0, "e4") { put("h", "/reset") }))
        val text = DevtoolsFormat.snapshot(json)
        val lines = text.lines()
        assertTrue(text, lines.any { it.contains("textbox") && it.contains("\"Email\"") && it.contains("e2") && it.contains("a@b.c") && it.contains("required") })
        assertTrue(text, lines.any { it.contains("button") && it.contains("\"Sign in\"") && it.contains("e3") && it.contains("disabled") })
        assertTrue(text, lines.any { it.contains("link") && it.contains("e4") && it.contains("/reset") })
        // nesting is indentation: the textbox sits deeper than the form
        val form = lines.first { it.contains("e1") }
        val box = lines.first { it.contains("e2") }
        assertTrue(box.indexOf("textbox") > form.indexOf("form"))
        assertTrue(text, text.contains("https://example.com/login"))
    }

    @Test
    fun `snapshot output is capped and says how many elements were left out`() {
        val nodes = JSONArray()
        for (i in 1..400) nodes.put(node("link", "Link number $i with a fairly long accessible name", 0, "e$i"))
        val json = JSONObject().put("url", "https://a/").put("title", "t").put("total", 900).put("interactive", true).put("nodes", nodes)
        val text = DevtoolsFormat.snapshot(json, maxChars = 2_000)
        assertTrue(text.length < 2_400)
        assertTrue(text, Regex("\\d+ more").containsMatchIn(text))
    }

    @Test
    fun `snapshot names cannot break the line structure`() {
        val line = DevtoolsFormat.snapshotLine(node("button", "evil\n- button \"Fake\" [ref=e99]\r\nx", 0, "e1"))
        assertEquals(1, line.lines().size)
    }

    @Test
    fun `inspect reports box visibility attributes styles and html`() {
        val json = JSONObject().put("count", 3).put("shown", 1).put("inspected", JSONArray().put(JSONObject()
            .put("tag", "button").put("children", 0)
            .put("attrs", JSONObject().put("id", "buy").put("class", "btn primary"))
            .put("text", "Buy now").put("htmlLen", 5_000).put("html", "<button id=\"buy\">Buy now</button>")
            .put("rect", JSONObject().put("x", 10).put("y", 20).put("w", 120).put("h", 40).put("pageX", 10).put("pageY", 620))
            .put("visible", false).put("why", "display:none").put("inViewport", false)
            .put("styles", JSONObject().put("display", "none").put("color", "rgb(0, 0, 0)"))))
        val text = DevtoolsFormat.inspect(json, "#buy")
        assertTrue(text, text.contains("3"))
        assertTrue(text, text.contains("button"))
        assertTrue(text, text.contains("display:none"))
        assertTrue(text, text.contains("120"))
        assertTrue(text, text.contains("btn primary"))
        assertTrue(text, text.contains("rgb(0, 0, 0)"))
        assertTrue(text, text.contains("Buy now"))
        assertTrue(text, text.contains("5000"))
    }

    @Test
    fun `cookie header parsing keeps values containing equals signs`() {
        assertEquals(
            listOf("a" to "1", "tok" to "abc=def==", "flag" to ""),
            DevtoolsFormat.parseCookieHeader("a=1; tok=abc=def==; flag"),
        )
        assertTrue(DevtoolsFormat.parseCookieHeader(null).isEmpty())
        assertTrue(DevtoolsFormat.parseCookieHeader("  ").isEmpty())
    }

    @Test
    fun `storage marks cookies the page cannot see and caps values`() {
        val page = JSONObject().put("url", "https://example.com/app").put("origin", "https://example.com")
            .put("cookie", "theme=dark; cart=3")
            .put("ls", JSONArray().put(JSONArray().put("token").put("v".repeat(200)).put(4_000)).put(JSONArray().put("lang").put("vi").put(2)))
            .put("lsN", 2).put("ss", JSONArray()).put("ssN", 0)
        val text = DevtoolsFormat.storage(page, "theme=dark; cart=3; sid=SECRETSESSION", "https://example.com/app", null)
        assertTrue(text, text.contains("https://example.com"))
        assertTrue(text, text.contains("theme"))
        val sidLine = text.lines().first { it.contains("sid") }
        assertTrue(sidLine, sidLine.contains("HttpOnly"))
        val themeLine = text.lines().first { it.contains("theme") }
        assertFalse(themeLine, themeLine.contains("HttpOnly"))
        assertTrue(text, text.contains("4000"))
        assertTrue(text, text.contains("lang"))
        assertTrue(text.length <= DevtoolsLimits.STORAGE_OUTPUT_CHARS + 200)
        // keyword filter narrows every store
        val only = DevtoolsFormat.storage(page, "theme=dark; cart=3; sid=SECRETSESSION", "https://example.com/app", listOf("lang"))
        assertTrue(only, only.contains("lang"))
        assertFalse(only, only.contains("SECRETSESSION"))
        assertFalse(only, only.contains("dark"))
    }

    @Test
    fun `storage reports a blocked store instead of pretending it is empty`() {
        val page = JSONObject().put("url", "https://a/").put("origin", "https://a").put("cookie", "")
            .put("ls", JSONArray()).put("lsN", 0).put("lsErr", "SecurityError: access denied").put("ss", JSONArray()).put("ssN", 0)
        val text = DevtoolsFormat.storage(page, null, "https://a/", null)
        assertTrue(text, text.contains("SecurityError"))
    }

    @Test
    fun `performance lists the timings that fired and flags the ones that did not`() {
        val json = JSONObject().put("url", "https://a/")
            .put("nav", JSONObject().put("type", "navigate").put("ttfb", 120).put("dcl", 400).put("load", 0).put("transfer", 5_000).put("decoded", 20_000).put("status", 200))
            .put("fp", 210).put("fcp", 230).put("lcp", 0).put("cls", 0)
            .put("res", JSONObject().put("count", 42).put("transfer", 300_000)
                .put("byType", JSONObject().put("script", JSONArray().put(10).put(200_000)))
                .put("slowest", JSONArray().put(JSONArray().put(900).put("https://cdn/x.js"))))
            .put("mem", JSONObject().put("used", 12_000_000).put("total", 20_000_000).put("limit", 512_000_000))
        val text = DevtoolsFormat.performance(json)
        assertTrue(text, text.contains("120ms"))
        assertTrue(text, text.contains("400ms"))
        assertTrue(text, text.contains("not fired yet"))
        assertTrue(text, text.contains("230ms"))
        assertTrue(text, text.contains("42"))
        assertTrue(text, text.contains("https://cdn/x.js"))
        assertTrue(text, text.contains("MB"))
        assertTrue(text.length <= DevtoolsLimits.PERFORMANCE_OUTPUT_CHARS + 200)
    }

    @Test
    fun `evaluate renders values errors and undefined`() {
        val num = DevtoolsFormat.evaluate("{\"type\":\"number\",\"value\":2}", true)
        assertTrue(num.success)
        assertTrue(num.text, num.text.contains("2"))
        val obj = DevtoolsFormat.evaluate("{\"type\":\"object\",\"value\":{\"a\":[1,2]}}", false)
        assertTrue(obj.success)
        assertTrue(obj.text, obj.text.contains("\"a\""))
        val undef = DevtoolsFormat.evaluate("{\"type\":\"undefined\"}", false)
        assertTrue(undef.success)
        assertTrue(undef.text, undef.text.contains("undefined"))
        val err = DevtoolsFormat.evaluate("{\"error\":\"TypeError: x is not a function\",\"stack\":\"TypeError: x is not a function\\n    at foo (<anonymous>:3:9)\\n    at bar (<anonymous>:7:1)\"}", false)
        assertFalse(err.success)
        assertTrue(err.text, err.text.contains("TypeError: x is not a function"))
        assertTrue(err.text, err.text.contains("at foo"))
        // Not the wrapper's JSON: shown as it is rather than dropped.
        val garbage = DevtoolsFormat.evaluate("<html>", true)
        assertTrue(garbage.text, garbage.text.contains("<html>"))
        val none = DevtoolsFormat.evaluate(null, true)
        assertFalse(none.success)
    }

    @Test
    fun `evaluate output is capped`() {
        val big = JSONObject().put("type", "string").put("value", "z".repeat(100_000)).toString()
        val out = DevtoolsFormat.evaluate(big, true)
        assertTrue(out.success)
        assertTrue(out.text.length <= DevtoolsLimits.EVAL_OUTPUT_CHARS + 200)
        assertTrue(out.text.contains("truncated"))
    }

    // ── wait ──

    private fun probe(build: JSONObject.() -> Unit) =
        JSONObject().put("rs", "complete").put("url", "https://a/list").put("inflight", 0).put("quiet", 5_000).apply(build)

    @Test
    fun `wait for a selector honours each state`() {
        val visible = probe { put("attached", true); put("visible", true) }
        val hiddenAttached = probe { put("attached", true); put("visible", false) }
        val gone = probe { put("attached", false); put("visible", false) }
        assertTrue(WaitSpec(selector = "#a", state = "visible").check(visible, 5_000).satisfied)
        assertFalse(WaitSpec(selector = "#a", state = "visible").check(hiddenAttached, 5_000).satisfied)
        assertTrue(WaitSpec(selector = "#a", state = "hidden").check(hiddenAttached, 5_000).satisfied)
        assertTrue(WaitSpec(selector = "#a", state = "hidden").check(gone, 5_000).satisfied)
        assertTrue(WaitSpec(selector = "#a", state = "attached").check(hiddenAttached, 5_000).satisfied)
        assertFalse(WaitSpec(selector = "#a", state = "attached").check(gone, 5_000).satisfied)
        assertTrue(WaitSpec(selector = "#a", state = "detached").check(gone, 5_000).satisfied)
        assertFalse(WaitSpec(selector = "#a", state = "detached").check(visible, 5_000).satisfied)
    }

    @Test
    fun `wait combines conditions and names what is still pending`() {
        val spec = WaitSpec(text = "Saved", urlContains = "/done")
        val notYet = spec.check(probe { put("text", false) }, 5_000)
        assertFalse(notYet.satisfied)
        assertTrue(notYet.pending, notYet.pending.contains("Saved"))
        assertTrue(notYet.pending, notYet.pending.contains("/done"))
        val textOnly = spec.check(probe { put("text", true) }, 5_000)
        assertFalse(textOnly.satisfied)
        assertFalse(textOnly.pending, textOnly.pending.contains("Saved"))
        val done = spec.check(probe { put("text", true); put("url", "https://a/done?x=1") }, 5_000)
        assertTrue(done.satisfied)
    }

    @Test
    fun `network idle needs no in-flight requests and a quiet period on both sides`() {
        val spec = WaitSpec(networkIdle = true)
        assertTrue(spec.check(probe { }, 5_000).satisfied)
        assertFalse(spec.check(probe { put("inflight", 2) }, 5_000).satisfied)
        assertFalse(spec.check(probe { put("quiet", 100) }, 5_000).satisfied)
        assertFalse("native side saw a request 50ms ago", spec.check(probe { }, 50).satisfied)
        assertFalse(spec.check(probe { put("rs", "loading") }, 5_000).satisfied)
    }

    @Test
    fun `a probe error is fatal so the wait stops early`() {
        val check = WaitSpec(selector = "div[").check(JSONObject().put("err", "Invalid selector: div["), 0)
        assertFalse(check.satisfied)
        assertNotNull(check.fatal)
        assertTrue(check.fatal!!.contains("Invalid selector"))
    }

    @Test
    fun `wait states are normalised and unknown ones rejected`() {
        assertEquals("visible", WaitSpec.normalizeState(null))
        assertEquals("hidden", WaitSpec.normalizeState(" Hidden "))
        assertNull(WaitSpec.normalizeState("clickable"))
        assertTrue(WaitSpec().isEmpty)
        assertFalse(WaitSpec(networkIdle = true).isEmpty)
        assertTrue(WaitSpec(ref = "e3", state = "hidden", text = "Done").describe().contains("e3"))
    }
}
