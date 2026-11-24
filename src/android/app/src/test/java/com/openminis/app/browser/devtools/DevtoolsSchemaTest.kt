package com.openminis.app.browser.devtools

import com.openminis.app.ProductionSources
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.tools.BrowserUseTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [M5-browser-devtools] What the model is told must match what the code accepts. */
class DevtoolsSchemaTest {

    private val newActions = listOf(
        "snapshot", "wait", "select_option", "console", "network", "evaluate", "inspect", "storage", "performance",
    )

    @Test
    fun `every new action is an enum value described to the model`() {
        for (a in newActions) {
            assertTrue("$a missing from BrowserAction", BrowserAction.allValues.contains(a))
            assertTrue("$a missing from the description", DevtoolsSchema.DESCRIPTION.contains(a))
            assertNotNull("$a must parse", BrowserActionInput.parse("""{"action":"$a"}"""))
        }
        // The pre-existing actions are still there, in front.
        assertEquals("navigate", BrowserAction.allValues.first())
        assertTrue(BrowserAction.allValues.containsAll(listOf("click", "type", "execute_js", "wait_for_dom_stable", "get_cookies")))
    }

    @Test
    fun `every declared parameter is one the parser reads and none shadows an existing one`() {
        val parser = ProductionSources.read("browser/BrowserActionInput.kt")
        val names = DevtoolsSchema.PARAMS.map { it.name }
        assertEquals("duplicate parameter names", names.size, names.toSet().size)
        for (p in DevtoolsSchema.PARAMS) {
            assertTrue("parser does not read '${p.name}'", parser.contains("\"${p.name}\""))
            assertTrue("bad type for ${p.name}", p.type in setOf("string", "integer", "boolean"))
            assertTrue("description of ${p.name} too long", p.description.length <= 260)
        }
        val existing = setOf(
            "tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount",
            "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout",
            "viewport_width", "viewport_height", "reset", "full_page",
        )
        assertTrue("new parameters must not redefine existing ones", names.none { it in existing })
    }

    @Test
    fun `texts stay short enough to be worth their tokens`() {
        assertTrue("description is ${DevtoolsSchema.DESCRIPTION.length} chars", DevtoolsSchema.DESCRIPTION.length <= 2_200)
        assertTrue("prompt hint is ${DevtoolsSchema.PROMPT_HINT.length} chars", DevtoolsSchema.PROMPT_HINT.length <= 420)
        assertFalse(DevtoolsSchema.PROMPT_HINT.contains("\n"))
        // The description tells the model that evaluate is as powerful as page script.
        assertTrue(DevtoolsSchema.DESCRIPTION.contains("page's own privileges"))
        assertTrue(DevtoolsSchema.DESCRIPTION.contains("current page only"))
    }

    @Test
    fun `mirror tool definition carries the new parameters`() {
        val def = BrowserUseTool.toolDefinition()
        assertEquals("browser_use", def.getString("name"))
        val props = def.getJSONObject("input_schema").getJSONObject("properties")
        for (p in DevtoolsSchema.PARAMS) {
            assertTrue("mirror lacks ${p.name}", props.has(p.name))
            assertEquals(p.type, props.getJSONObject(p.name).getString("type"))
        }
        assertEquals(WaitSpec.STATES.size, props.getJSONObject("state").getJSONArray("enum").length())
        val actions = props.getJSONObject("action").getJSONArray("enum")
        val list = (0 until actions.length()).map { actions.getString(it) }
        assertTrue(list.containsAll(newActions))
        assertTrue(def.getString("description").contains("snapshot"))
    }
}
