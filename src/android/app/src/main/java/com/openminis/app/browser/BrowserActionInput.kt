package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed input for a browser_use tool call, mirroring iOS BrowserActionInput.
 */
data class BrowserActionInput(
    val action: BrowserAction,
    val url: String? = null,
    val selector: String? = null,
    val text: String? = null,
    val coordinateX: Int? = null,
    val coordinateY: Int? = null,
    val direction: ScrollDirection? = null,
    val amount: Int? = null,
    val script: String? = null,
    val userAgent: UserAgentProfile? = null,
    /** [ISS-033] The user_agent argument as given, for the error text when it is unknown. */
    val userAgentRaw: String? = null,
    val maxDepth: Int? = null,
    val tabId: Int? = null,
    /** Viewport width override (set_viewport action). */
    val viewportWidth: Int? = null,
    /** Viewport height override (set_viewport action). */
    val viewportHeight: Int? = null,
    /** When true on set_viewport, drop the session-level viewport override. */
    val reset: Boolean = false,
    /** Cookie-name / scroll_and_collect keyword filter. */
    val keywords: List<String>? = null,
    /** Fuzzy-match cookie names for get_cookies. */
    val fuzzy: Boolean = false,
    /** Item selector for scroll_and_collect (each matching element is captured). */
    val itemSelector: String? = null,
    /** Scroll iterations for scroll_and_collect. */
    val scrollCount: Int? = null,
    /** Milliseconds to wait (wait_for_dom_stable). */
    val timeoutMs: Int? = null,
    /** When true on screenshot, stretch viewport to full document.scrollHeight before capture. */
    val fullPage: Boolean = false,
    /**
     * Cookies to write (set_cookies). Each entry has keys name + value
     * (required) and optional domain, path, secure, http_only, expires
     * (Unix seconds). Values are loosely typed (String / Boolean / Number).
     */
    val cookies: List<Map<String, Any?>>? = null,
    // -- [M5-browser-devtools] ------------------------------------------------
    /**
     * Element ref from the last `snapshot` (e.g. "e12"), already normalised by
     * [com.openminis.app.browser.devtools.DevtoolsLimits.normalizeRef]. An
     * alternative to `selector` for click / type / hover / get_text /
     * select_option / inspect / wait.
     */
    val ref: String? = null,
    /** The `ref` argument as given, for the error text when it is not a ref. */
    val refRaw: String? = null,
    /** console: minimum level (error | warn | info | log | debug | all). */
    val level: String? = null,
    /** console / network: only entries with a sequence number above this cursor. */
    val since: Int? = null,
    /** console / network: empty the buffer after reading. */
    val clear: Boolean = false,
    /** console / network / snapshot / inspect: how many entries or elements to return. */
    val limit: Int? = null,
    /** console: message substring; network: URL substring, resource type or method. */
    val filter: String? = null,
    /** network: only failed requests and HTTP status >= 400. */
    val failed: Boolean = false,
    /** network: show one request (its `#id` from the list) in detail. */
    val requestId: Int? = null,
    /** wait: visible | hidden | attached | detached (for selector / ref). */
    val state: String? = null,
    /** wait: resolve when the page URL contains this substring. */
    val urlContains: String? = null,
    /** wait: resolve when the network has been quiet for a moment. */
    val networkIdle: Boolean = false,
    /** inspect: computed-style property names to report (default: a useful set). */
    val properties: List<String>? = null,
    /** snapshot: false also lists headings, images and text (default true = interactive only). */
    val interactive: Boolean = true,
    /** snapshot: include link targets. */
    val urls: Boolean = false,
) {
    companion object {
        fun parse(json: String): BrowserActionInput? {
            return try {
                val obj = JSONObject(json)
                val actionStr = obj.optString("action", "")
                val action = BrowserAction.fromString(actionStr) ?: return null

                BrowserActionInput(
                    action = action,
                    url = obj.optString("url").ifEmpty { null },
                    selector = obj.optString("selector").ifEmpty { null },
                    text = obj.optString("text").ifEmpty { null },
                    coordinateX = if (obj.has("coordinate_x")) obj.optInt("coordinate_x") else null,
                    coordinateY = if (obj.has("coordinate_y")) obj.optInt("coordinate_y") else null,
                    direction = obj.optString("direction").ifEmpty { null }?.let { ScrollDirection.fromString(it) },
                    amount = if (obj.has("amount")) obj.optInt("amount") else null,
                    script = obj.optString("script").ifEmpty { null },
                    userAgent = UserAgentProfile.parse(obj.optString("user_agent").ifEmpty { null }),
                    userAgentRaw = obj.optString("user_agent").ifEmpty { null },
                    maxDepth = if (obj.has("max_depth")) obj.optInt("max_depth") else null,
                    tabId = if (obj.has("tab_id")) obj.optInt("tab_id") else null,
                    viewportWidth = if (obj.has("viewport_width")) obj.optInt("viewport_width") else null,
                    viewportHeight = if (obj.has("viewport_height")) obj.optInt("viewport_height") else null,
                    reset = obj.optBoolean("reset", false),
                    keywords = obj.optJSONArray("keywords")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                            .takeIf { it.isNotEmpty() }
                    },
                    fuzzy = obj.optBoolean("fuzzy", false),
                    itemSelector = obj.optString("item_selector").ifEmpty { null },
                    scrollCount = if (obj.has("scroll_count")) obj.optInt("scroll_count") else null,
                    timeoutMs = if (obj.has("timeout")) obj.optInt("timeout") else null,
                    fullPage = obj.optBoolean("full_page", false),
                    cookies = parseCookies(obj),
                    ref = com.openminis.app.browser.devtools.DevtoolsLimits.normalizeRef(optScalar(obj, "ref")),
                    refRaw = optScalar(obj, "ref"),
                    level = obj.optString("level").ifEmpty { null },
                    since = if (obj.has("since")) obj.optInt("since") else null,
                    clear = obj.optBoolean("clear", false),
                    limit = if (obj.has("limit")) obj.optInt("limit") else null,
                    filter = obj.optString("filter").ifEmpty { null },
                    failed = obj.optBoolean("failed", false),
                    requestId = if (obj.has("request_id")) obj.optInt("request_id") else null,
                    state = obj.optString("state").ifEmpty { null },
                    urlContains = obj.optString("url_contains").ifEmpty { null },
                    networkIdle = obj.optBoolean("network_idle", false),
                    properties = parseStringList(obj, "properties"),
                    interactive = obj.optBoolean("interactive", true),
                    urls = obj.optBoolean("urls", false),
                )
            } catch (_: Exception) {
                null
            }
        }

        /** A string-or-number argument as text (a model may send `"ref": 12`); null when absent or blank. */
        private fun optScalar(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** A list argument given as a JSON array or as one comma/space separated string. */
        private fun parseStringList(obj: JSONObject, key: String): List<String>? {
            val arr: JSONArray? = obj.optJSONArray(key)
            val items: List<String> = if (arr != null) {
                (0 until arr.length()).map { arr.optString(it) }
            } else {
                obj.optString(key).split(',', ' ', '\n')
            }
            return items.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        }

        /**
         * `cookies` may arrive either as a real JSON array or — because the tool
         * schema can only type it as a string — as a JSON-encoded STRING like
         * `"[{\"name\":...}]"`. Accept both so a schema-faithful model (which
         * emits the string form) isn't silently dropped to empty. Returns null
         * when absent / unparseable / empty.
         */
        private fun parseCookies(obj: JSONObject): List<Map<String, Any?>>? {
            val arr: JSONArray? = obj.optJSONArray("cookies")
                ?: obj.optString("cookies").takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
            arr ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { co ->
                    co.keys().asSequence().associateWith { k -> co.get(k) }
                }
            }.takeIf { it.isNotEmpty() }
        }
    }
}
