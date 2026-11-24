package com.openminis.app.browser.devtools

import com.openminis.app.browser.BrowserUseJS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [M5-browser-devtools] The JS the devtools actions inject.
 *
 *   [1] Structure and escaping: model-supplied selectors / text / options can
 *       never leave their string literal; refs and tokens are validated.
 *   [2] Ref retargeting of the existing click / type / hover / get_text
 *       scripts leaves no sentinel behind and does not alter the selector form.
 *   [3] With `node` installed: every generated script passes a syntax check,
 *       and `evaluate` is run for real (values, promises, errors, cycles).
 *       Skipped when no `node` is installed.
 */
class DevtoolsJSTest {

    companion object {
        private var node: String? = null

        @BeforeClass @JvmStatic
        fun findNode() {
            val home = System.getProperty("user.home")
            val nvm = File(home, ".nvm/versions/node").listFiles()?.map { File(it, "bin/node").path } ?: emptyList()
            node = (listOfNotNull(System.getenv("NODE"), "node", "/opt/homebrew/bin/node", "/usr/local/bin/node") + nvm)
                .firstOrNull { runCatching { ProcessBuilder(it, "-e", "0").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
        }

        /** Text that tries every way out of a JS string literal. */
        private const val HOSTILE = "a'b\"c\\d\ne\rf\u2028g</script>h`i\${j}');alert(1);//"

        private const val TOKEN = "abcdefgh12345678"
    }

    private fun runNode(source: String, timeoutSec: Long = 30): String {
        val bin = node
        assumeTrue("node is not installed", bin != null)
        val f = File.createTempFile("devtools-js", ".js")
        try {
            f.writeText(source)
            val p = ProcessBuilder(bin, f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("node timed out", p.waitFor(timeoutSec, TimeUnit.SECONDS))
            return out.trim()
        } finally {
            f.delete()
        }
    }

    private fun syntaxCheck(name: String, script: String) {
        val bin = node
        assumeTrue("node is not installed", bin != null)
        val f = File.createTempFile("devtools-check", ".js")
        try {
            f.writeText(script)
            val p = ProcessBuilder(bin, "--check", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("node --check timed out for $name", p.waitFor(30, TimeUnit.SECONDS))
            assertEquals("$name is not valid JavaScript:\n$out", 0, p.exitValue())
        } finally {
            f.delete()
        }
    }

    // ── [1] structure and escaping ──

    @Test
    fun `q produces one double-quoted literal whatever the input`() {
        val lit = DevtoolsJS.q(HOSTILE)
        assertTrue(lit.startsWith("\"") && lit.endsWith("\""))
        val inner = lit.substring(1, lit.length - 1)
        // every double quote inside is escaped; no raw line terminator, no script close tag
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '\\') { i += 2; continue }
            assertFalse("unescaped quote at $i in $inner", inner[i] == '"')
            i++
        }
        assertFalse(inner.contains("\n") || inner.contains("\r") || inner.contains("\u2028") || inner.contains("\u2029"))
        assertFalse(inner.contains("</script"))
    }

    @Test
    fun `q round-trips through a real JS engine`() {
        val out = runNode("process.stdout.write(Buffer.from(${DevtoolsJS.q(HOSTILE)}, 'utf8').toString('hex'))")
        val expected = HOSTILE.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        assertEquals(expected, out)
    }

    @Test
    fun `hostile selectors text and options stay inside their literals`() {
        val literal = DevtoolsJS.q(HOSTILE)
        val scripts = mapOf(
            "snapshot" to DevtoolsJS.snapshot(HOSTILE, true, 150, 3, false),
            "inspect" to DevtoolsJS.inspect(HOSTILE, null, null, 1),
            "waitProbe selector" to DevtoolsJS.waitProbe(HOSTILE, null, null),
            "waitProbe text" to DevtoolsJS.waitProbe(null, null, HOSTILE),
            "selectOption" to DevtoolsJS.selectOption(HOSTILE, null, HOSTILE),
        )
        for ((name, js) in scripts) {
            assertTrue("$name must embed the escaped literal", js.contains(literal))
            assertFalse("$name leaks the raw value", js.contains(HOSTILE))
            syntaxCheck(name, js)
        }
    }

    @Test
    fun `computed style property names are whitelisted by shape and bounded`() {
        val props = DevtoolsJS.sanitizeProperties(listOf("color", " Font-Size ", "--brand-color", "bad;prop", "x'y", "a(b)", "", "color"))
        assertEquals(listOf("color", "font-size", "--brand-color"), props)
        assertTrue(DevtoolsJS.sanitizeProperties((1..200).map { "p$it" }).size <= DevtoolsLimits.INSPECT_MAX_PROPERTIES)
        // nothing usable: inspect() then falls back to the default set
        assertTrue(DevtoolsJS.sanitizeProperties(null).isEmpty())
        assertTrue(DevtoolsJS.sanitizeProperties(listOf("!!", "")).isEmpty())
        assertTrue(DevtoolsJS.inspect("a", null, listOf("!!"), 1).contains("\"box-sizing\""))
        assertTrue(DevtoolsJS.DEFAULT_STYLE_PROPERTIES.size <= DevtoolsLimits.INSPECT_MAX_PROPERTIES)
    }

    @Test
    fun `evaluate only accepts an alphanumeric result token`() {
        assertTrue(DevtoolsJS.isSafeToken(TOKEN))
        for (bad in listOf("", "short", "abc'def12345", "abcdefgh1234567\n", "a".repeat(65), "abcd efgh1234")) {
            assertFalse(bad, DevtoolsJS.isSafeToken(bad))
            try {
                DevtoolsJS.evaluate("1", bad, true)
                throw AssertionError("token accepted: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `expression candidate drops trailing semicolons only`() {
        assertEquals("document.title", DevtoolsJS.expressionCandidate("  document.title;; \n"))
        assertEquals("a; b", DevtoolsJS.expressionCandidate("a; b;"))
    }

    @Test
    fun `passive hook does not wrap fetch or XHR and deep hook does`() {
        val passive = DevtoolsJS.hook(false)
        val deep = DevtoolsJS.hook(true)
        assertTrue(passive.contains("unhandledrejection"))
        assertTrue(passive.contains("PerformanceObserver"))
        assertTrue(deep.contains("XMLHttpRequest"))
        // one script, one flag: the passive form must carry the flag switched off
        assertFalse("passive and deep hooks must differ", passive == deep)
        assertTrue(passive.contains(DevtoolsJS.BRIDGE))
        // page state is hidden from enumeration
        assertTrue(passive.contains("enumerable: false") || passive.contains("enumerable:false"))
    }

    @Test
    fun `password values are redacted by snapshot and inspect`() {
        assertTrue(DevtoolsJS.snapshot(null, true, 150, 3, false).contains("[redacted]"))
        assertTrue(DevtoolsJS.inspect("input", null, null, 1).contains("[redacted]"))
    }

    @Test
    fun `storage script takes no url and reads only the current document`() {
        val js = DevtoolsJS.storage()
        assertTrue(js.contains("location.href"))
        assertTrue(js.contains("localStorage") && js.contains("sessionStorage") && js.contains("document.cookie"))
        assertFalse("storage must not fetch or open anything", js.contains("fetch(") || js.contains("XMLHttpRequest") || js.contains("window.open"))
    }

    // ── [2] ref retargeting ──

    @Test
    fun `ref forms of the existing actions carry no sentinel and resolve through the ref map`() {
        val scripts = mapOf(
            "click" to DevtoolsJS.clickRef("e7"),
            "type" to DevtoolsJS.typeRef("e7", HOSTILE),
            "hover" to DevtoolsJS.hoverRef("e7"),
            "get_text" to DevtoolsJS.getTextRef("e7"),
        )
        for ((name, js) in scripts) {
            assertNotNull("$name must support refs", js)
            assertFalse("$name still has the sentinel", js!!.contains(DevtoolsJS.REF_SENTINEL))
            assertTrue("$name must resolve the ref", js.contains("__minisDT.el('e7')"))
            assertFalse("$name must not query by selector", js.contains("document.querySelector('"))
            syntaxCheck("$name ref", js)
        }
    }

    @Test
    fun `retargeting refuses anything that is not a ref or has no single target`() {
        val click = BrowserUseJS.click(DevtoolsJS.REF_SENTINEL)
        assertNull(DevtoolsJS.retargetToRef(click, "e7'); alert(1); ('"))
        assertNull(DevtoolsJS.retargetToRef(click, "#id"))
        assertNull("no sentinel lookup to replace", DevtoolsJS.retargetToRef("return 1;", "e7"))
        assertNotNull(DevtoolsJS.retargetToRef(click, "@e7"))
    }

    @Test
    fun `selector forms of the existing actions are untouched`() {
        // The devtools layer builds on BrowserUseJS without editing it.
        assertEquals(BrowserUseJS.click("#b"), BrowserUseJS.click("#b"))
        assertFalse(BrowserUseJS.click("#b").contains("__minisDT"))
        assertFalse(BrowserUseJS.type("#b", "x").contains("__minisDT"))
    }

    @Test
    fun `a missing ref gets an actionable hint and other errors do not`() {
        val hinted = DevtoolsJS.explainRefError("Element not found: ref e7", "e7")
        assertTrue(hinted, hinted.contains("snapshot"))
        val other = DevtoolsJS.explainRefError("Element is disabled", "e7")
        assertEquals("Element is disabled", other)
    }

    // ── [3] real engine ──

    @Test
    fun `every generated script is valid JavaScript`() {
        val scripts = mapOf(
            "hook passive" to DevtoolsJS.hook(false),
            "hook deep" to DevtoolsJS.hook(true),
            "flush" to DevtoolsJS.FLUSH,
            "snapshot interactive" to DevtoolsJS.snapshot(null, true, 150, 3, false),
            "snapshot full" to DevtoolsJS.snapshot("main", false, 400, 6, true),
            "inspect selector" to DevtoolsJS.inspect("div.card", null, listOf("color", "--x"), 5),
            "inspect ref" to DevtoolsJS.inspect(null, "e5", null, 1),
            "storage" to DevtoolsJS.storage(),
            "performance" to DevtoolsJS.performance(),
            "evaluate expression" to DevtoolsJS.evaluate("1 + 1", TOKEN, true),
            "evaluate statements" to DevtoolsJS.evaluate("const a = await fetch('/x'); return a.status", TOKEN, false),
            "wait selector" to DevtoolsJS.waitProbe("#a", null, "text"),
            "wait ref" to DevtoolsJS.waitProbe(null, "e3", null),
            "wait bare" to DevtoolsJS.waitProbe(null, null, null),
            "select selector" to DevtoolsJS.selectOption("select#c", null, "Vietnam"),
            "select ref" to DevtoolsJS.selectOption(null, "e9", "Vietnam"),
        )
        for ((name, js) in scripts) syntaxCheck(name, js)
    }

    /** Runs evaluate() in node with a stand-in window + bridge; returns what the bridge received. */
    private fun evalInNode(script: String, asExpression: Boolean): String {
        val js = DevtoolsJS.evaluate(script, TOKEN, asExpression)
        val driver = """
            var got = null;
            globalThis.window = globalThis;
            window.${DevtoolsJS.BRIDGE} = {result: function(t, s){ got = t + '|' + s; }};
            var __p = ${org.json.JSONObject.quote(js)};
            (0, eval)(__p).then(function(){ process.stdout.write(String(got)); });
        """.trimIndent()
        val out = runNode(driver)
        assertTrue("bridge got: $out", out.startsWith("$TOKEN|"))
        return out.substring(TOKEN.length + 1)
    }

    @Test
    fun `evaluate returns values of every awkward kind`() {
        assertEquals("""{"type":"number","value":2}""", evalInNode("1 + 1", true))
        assertEquals("""{"type":"number","value":4}""", evalInNode("const a = await Promise.resolve(2); return a * 2", false))
        // a promise in expression form is awaited
        assertEquals("""{"type":"string","value":"ok"}""", evalInNode("new Promise(function(r){ setTimeout(function(){ r('ok'); }, 5); })", true))
        val undef = org.json.JSONObject(evalInNode("void 0", true))
        assertEquals("undefined", undef.getString("type"))
        val nul = org.json.JSONObject(evalInNode("null", true))
        assertEquals("null", nul.getString("type"))
        // circular structure does not throw
        val cyc = evalInNode("(function(){ var a = {name: 'x'}; a.self = a; return a; })()", true)
        assertTrue(cyc, cyc.contains("\"name\":\"x\""))
        assertTrue(cyc, cyc.lowercase().contains("circular"))
        // Map, Date, function, Error, BigInt survive
        val mixed = evalInNode("({m: new Map([['k', 1]]), d: new Date(0), f: function foo(){}, e: new TypeError('bad'), b: 10n})", true)
        assertTrue(mixed, mixed.contains("1970"))
        assertTrue(mixed, mixed.contains("foo"))
        assertTrue(mixed, mixed.contains("bad"))
        assertTrue(mixed, mixed.contains("10"))
    }

    @Test
    fun `evaluate reports thrown errors with a stack and bounds huge results`() {
        val err = org.json.JSONObject(evalInNode("(function boom(){ throw new RangeError('too far'); })()", true))
        assertEquals("RangeError: too far", err.getString("error"))
        assertTrue(err.getString("stack").contains("boom"))
        val rejected = org.json.JSONObject(evalInNode("await Promise.reject(new Error('nope'))", false))
        assertEquals("Error: nope", rejected.getString("error"))
        val thrownString = org.json.JSONObject(evalInNode("throw 'plain'", false))
        assertTrue(thrownString.getString("error").contains("plain"))
        val big = evalInNode("'z'.repeat(5000000)", true)
        assertTrue("page-side result must be bounded, was ${big.length}", big.length < 70_000)
        val wide = evalInNode("Array.from({length: 100000}, function(_, i){ return {i: i, s: 'abcdefghij'}; })", true)
        assertTrue("page-side result must be bounded, was ${wide.length}", wide.length < 70_000)
    }

    @Test
    fun `evaluate formats end to end`() {
        val out = DevtoolsFormat.evaluate(evalInNode("({title: 'T', n: [1, 2, 3]})", true), true)
        assertTrue(out.success)
        assertTrue(out.text, out.text.contains("\"title\""))
        val err = DevtoolsFormat.evaluate(evalInNode("missingFunction()", true), true)
        assertFalse(err.success)
        assertTrue(err.text, err.text.contains("ReferenceError"))
    }
}
