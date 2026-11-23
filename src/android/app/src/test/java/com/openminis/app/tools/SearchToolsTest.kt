package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m4-search] The grep and glob tools: scripts, bounds and wiring. */
class SearchToolsTest {

    private fun args(vararg pairs: Pair<String, Any>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in pairs) obj.put(k, v)
        return obj
    }

    @Test
    fun `grep quotes everything the model supplies`() {
        val script = SearchTools.grepScript(
            args("pattern" to "it's \$HOME; rm -rf /", "path" to "src dir", "glob" to "*.kt"),
            "/root/my app",
        ).first
        assertNotNull(script)
        val s = script.orEmpty()
        assertTrue(s.startsWith("cd '/root/my app' || exit 97\n"))
        assertTrue(s.contains(" -e 'it'\\''s \$HOME; rm -rf /' -- 'src dir'"))
        assertTrue(s.contains(" -g '*.kt'"))
        assertTrue(s.contains("| head -n " + (SearchTools.DEFAULT_LIMIT + 1)))
    }

    @Test
    fun `grep flags follow the arguments`() {
        val plain = SearchTools.grepScript(args("pattern" to "x"), "/root").first.orEmpty()
        assertFalse(plain.contains(" -i"))
        assertFalse(plain.contains(" -F"))
        assertTrue(plain.contains(" -- '.'"))
        val flagged = SearchTools.grepScript(
            args("pattern" to "x", "ignore_case" to true, "fixed_strings" to true, "limit" to 5),
            "/root",
        ).first.orEmpty()
        assertTrue(flagged.contains(" -i"))
        assertTrue(flagged.contains(" -F"))
        assertTrue(flagged.contains("| head -n 6"))
    }

    @Test
    fun `missing pattern and multi-line path are errors`() {
        assertNull(SearchTools.grepScript(args("tool_title" to "t"), "/root").first)
        assertNull(SearchTools.globScript(args("pattern" to "  "), "/root").first)
        assertNull(SearchTools.grepScript(args("pattern" to "x", "path" to "a\nb"), "/root").first)
    }

    @Test
    fun `glob lists files sorted and bounded`() {
        val s = SearchTools.globScript(args("pattern" to "**/*.kt", "limit" to 100000), "/root/p").first.orEmpty()
        assertTrue(s.contains("rg --files --sort path --color never -g '**/*.kt' -- '.'"))
        assertTrue(s.contains("| head -n " + (SearchTools.MAX_LIMIT + 1)))
    }

    @Test
    fun `limits are clamped`() {
        assertEquals(SearchTools.DEFAULT_LIMIT, SearchTools.clampLimit(0))
        assertEquals(SearchTools.DEFAULT_LIMIT, SearchTools.clampLimit(-3))
        assertEquals(7, SearchTools.clampLimit(7))
        assertEquals(SearchTools.MAX_LIMIT, SearchTools.clampLimit(9999))
    }

    @Test
    fun `results are trimmed to the limit and say when there was more`() {
        assertEquals("No matches.", SearchTools.render("\n\n", 10, "No matches."))
        assertEquals("a.kt:1:x\nb.kt:2:y", SearchTools.render("./a.kt:1:x\nb.kt:2:y\n", 10, "none"))
        val more = SearchTools.render("a\nb\nc\n", 2, "none")
        assertTrue(more.startsWith("a\nb\n[more results not shown"))
    }

    @Test
    fun `both tools are offered and dispatched`() {
        val names = AgentTools.makeAgentTools().map { it.name }
        assertTrue(names.contains(SearchTools.GREP))
        assertTrue(names.contains(SearchTools.GLOB))
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("SearchTools.execute(name, argsJson, activeSessionId, fsSessionId, context)"))
    }
}
