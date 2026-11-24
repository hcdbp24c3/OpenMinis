package com.openminis.app.browser.devtools

/**
 * [M5-browser-devtools] The words the model reads about the devtools actions,
 * in one place so the live schema (tools/AgentTools.kt), its mirror
 * (tools/BrowserUseTool.kt) and the system prompt cannot drift apart.
 * Pure Kotlin - unit tested.
 */
object DevtoolsSchema {

    data class Param(
        val name: String,
        val type: String,
        val description: String,
        val enumValues: List<String>? = null,
    )

    /** Appended to the `browser_use` tool description. */
    const val DESCRIPTION: String =
        "Page model and devtools: " +
            "snapshot returns a compact outline of the page's interactive elements, one line each with a ref like [ref=e12] " +
            "(interactive=false adds headings, images and text; selector limits it to a subtree) - far cheaper than a screenshot or get_backbone. " +
            "click, type, hover, get_text, select_option, inspect and wait accept 'ref' instead of 'selector'. " +
            "Refs stay valid while the element stays in the page and die on navigation - take a new snapshot after the page changes. " +
            "select_option picks an option of a <select> by label or value ('text'). " +
            "wait blocks until every given condition holds: selector/ref with state (visible, hidden, attached, detached), 'text' present on the page, 'url_contains', 'network_idle' (default timeout 10s, max 30s). " +
            "console reads this tab's console messages, uncaught errors and unhandled promise rejections with source location and stack (level = minimum severity, since = cursor from the previous call, clear=true empties it). " +
            "network lists this tab's requests with method, status, type, size and duration (failed=true shows only errors, filter matches the URL); the first call also starts capturing fetch/XHR details, " +
            "after which request_id returns one request's headers and a response body preview (text/JSON, fetch/XHR only). " +
            "evaluate runs JavaScript ('script': an expression or statements, await allowed) and returns the value serialised - it runs with the page's own privileges, like a devtools console. " +
            "inspect reports match count, HTML, text, attributes, box, visibility and computed styles for a selector or ref. " +
            "storage lists cookies, localStorage and sessionStorage of the current page only. " +
            "performance reports navigation timing, paints, LCP, resource totals and JS heap."

    /** New `browser_use` parameters (existing ones - selector, text, script, max_depth, keywords, timeout - are reused). */
    val PARAMS: List<Param> = listOf(
        Param("ref", "string", "Element ref from the snapshot action (e.g. e12). Alternative to selector for click, type, hover, get_text, select_option, inspect and wait."),
        Param("interactive", "boolean", "For snapshot: true (default) lists only interactive elements and landmarks; false also lists headings, images and text."),
        Param("urls", "boolean", "For snapshot: include link targets (default false)."),
        Param("limit", "integer", "Maximum items to return: snapshot elements (default 150, max 400), console entries (default 50), network requests (default 40), inspect matches (default 1, max 5)."),
        Param("state", "string", "For wait with selector/ref: the state to wait for (default visible).", WaitSpec.STATES),
        Param("url_contains", "string", "For wait: resolve when the page URL contains this text."),
        Param("network_idle", "boolean", "For wait: resolve when no request has been in flight for 0.5s."),
        Param("level", "string", "For console: minimum severity to return (default: all).", listOf("debug", "log", "info", "warn", "error")),
        Param("since", "integer", "For console/network: return only entries after this cursor (each result ends with the cursor to pass next)."),
        Param("clear", "boolean", "For console/network: empty the buffer after reading."),
        Param("filter", "string", "For console: text the message must contain. For network: text the URL must contain."),
        Param("failed", "boolean", "For network: only failed requests and HTTP status >= 400."),
        Param("request_id", "integer", "For network: the #id of one request from the list, to get its headers and response body preview."),
        Param("properties", "string", "For inspect: comma-separated CSS properties to read from the computed style (default: a common layout/typography set)."),
    )

    /** Appended to the browser bullet of the system prompt. */
    const val PROMPT_HINT: String =
        " Prefer snapshot (compact page outline with element refs like e12) to find things, then click/type/select_option with ref; use wait instead of sleeping." +
            " To debug a page use console (errors with stacks), network (requests, failures, response previews), inspect (box/visibility/computed styles), evaluate (run JS, get the value back), storage and performance."
}
