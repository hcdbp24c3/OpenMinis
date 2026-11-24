package com.openminis.app.browser.devtools

import com.openminis.app.ProductionSources
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M5-browser-devtools] Argument parsing for the new actions, plus source
 * guards that the Android wiring (which cannot run in a unit test) is in place.
 */
class BrowserDevtoolsWiringTest {

    @Test
    fun `new actions and their arguments parse`() {
        val snap = BrowserActionInput.parse("""{"action":"snapshot","selector":"main","interactive":false,"limit":80,"max_depth":2,"urls":true}""")!!
        assertEquals(BrowserAction.SNAPSHOT, snap.action)
        assertEquals("main", snap.selector)
        assertEquals(false, snap.interactive)
        assertEquals(80, snap.limit)
        assertEquals(true, snap.urls)

        val wait = BrowserActionInput.parse("""{"action":"wait","ref":"@e4","state":"hidden","text":"Saved","url_contains":"/done","network_idle":true,"timeout":5}""")!!
        assertEquals(BrowserAction.WAIT, wait.action)
        assertEquals("e4", wait.ref)
        assertEquals("hidden", wait.state)
        assertEquals("/done", wait.urlContains)
        assertEquals(true, wait.networkIdle)

        val con = BrowserActionInput.parse("""{"action":"console","level":"warn","since":12,"clear":true,"filter":"cors"}""")!!
        assertEquals(BrowserAction.CONSOLE, con.action)
        assertEquals("warn", con.level)
        assertEquals(12, con.since)
        assertEquals(true, con.clear)
        assertEquals("cors", con.filter)

        val net = BrowserActionInput.parse("""{"action":"network","failed":true,"request_id":17}""")!!
        assertEquals(BrowserAction.NETWORK, net.action)
        assertEquals(true, net.failed)
        assertEquals(17, net.requestId)
        // the id is also accepted as a string
        assertEquals(17, BrowserActionInput.parse("""{"action":"network","request_id":"17"}""")!!.requestId)

        val insp = BrowserActionInput.parse("""{"action":"inspect","selector":".card","properties":["color","font-size"]}""")!!
        assertEquals(BrowserAction.INSPECT, insp.action)
        assertEquals(listOf("color", "font-size"), insp.properties)
        // a comma-separated string works as well as an array
        assertEquals(listOf("color", "font-size"), BrowserActionInput.parse("""{"action":"inspect","selector":"a","properties":"color, font-size"}""")!!.properties)

        for (name in listOf("select_option", "evaluate", "storage", "performance")) {
            assertNotNull(name, BrowserActionInput.parse("""{"action":"$name"}"""))
        }
    }

    @Test
    fun `a ref that is not a ref is kept raw so the call can be rejected`() {
        val ok = BrowserActionInput.parse("""{"action":"click","ref":"e12"}""")!!
        assertEquals("e12", ok.ref)
        val bad = BrowserActionInput.parse("""{"action":"click","ref":"#submit","selector":"#other"}""")!!
        assertNull(bad.ref)
        assertEquals("#submit", bad.refRaw)
        val none = BrowserActionInput.parse("""{"action":"click","selector":"#other"}""")!!
        assertNull(none.ref)
        assertNull(none.refRaw)
    }

    @Test
    fun `existing actions parse exactly as before`() {
        val click = BrowserActionInput.parse("""{"action":"click","selector":"#a","coordinate_x":3,"coordinate_y":4}""")!!
        assertEquals(BrowserAction.CLICK, click.action)
        assertEquals("#a", click.selector)
        assertEquals(3, click.coordinateX)
        assertNull(BrowserActionInput.parse("""{"action":"no_such_action"}"""))
        assertNull(BrowserActionInput.parse("not json"))
    }

    @Test
    fun `manager wires the devtools hooks and routes the new actions`() {
        val src = ProductionSources.read("browser/BrowserUseManager.kt")
        for (needle in listOf(
            "devtools.install()",
            "devtools.onPageStarted(view, url)",
            "devtools.onPageFinished(view)",
            "devtools.onConsoleMessage(consoleMessage)",
            "devtools.onRequest(request)",
            "devtools.onHttpError(request, errorResponse)",
            "devtools.onRequestFailed(request, error)",
            "return devtools.execute(input)",
            "input.refRaw != null && input.ref == null",
        )) {
            assertEquals("BrowserUseManager must contain exactly one: $needle", 1, src.split(needle).size - 1)
        }
        for (action in listOf("SNAPSHOT", "WAIT", "SELECT_OPTION", "CONSOLE", "NETWORK", "EVALUATE", "INSPECT", "STORAGE", "PERFORMANCE")) {
            assertTrue("manager must route $action", src.contains("BrowserAction.$action"))
        }
        // onConsoleMessage must keep WebView's own logcat output.
        val console = src.substringAfter("override fun onConsoleMessage(").substringBefore("override fun ")
        assertTrue(console.contains("return false"))
        // Recording a request must not change how it is served.
        val intercept = src.substringAfter("devtools.onRequest(request)").substringBefore("override fun ")
        assertTrue(intercept.contains("if (url.scheme != \"minis\") return null"))
    }

    @Test
    fun `devtools never reads cookies or storage for a caller-supplied url`() {
        val src = ProductionSources.read("browser/devtools/BrowserDevtools.kt")
        val storage = src.substringAfter("private suspend fun storage(").substringBefore("private fun originOf(")
        assertFalse("storage must not use the 'url' argument", storage.contains("input.url"))
        assertTrue(storage.contains("originOf(scriptUrl) == originOf(pageUrl)"))
        assertTrue(storage.contains("webView.url"))
    }

    @Test
    fun `the action guard file is untouched by the devtools work`() {
        val guard = ProductionSources.read("browser/BrowserActionGuard.kt")
        assertFalse(guard.contains("M5-browser-devtools"))
        assertFalse(guard.contains("devtools"))
    }
}
