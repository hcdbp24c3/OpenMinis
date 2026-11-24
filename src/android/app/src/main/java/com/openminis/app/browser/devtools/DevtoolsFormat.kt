package com.openminis.app.browser.devtools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [M5-browser-devtools] Turns the JSON the injected scripts return into the
 * compact text the model reads, and decides wait conditions. Pure Kotlin
 * (org.json only) - see DevtoolsFormatTest.
 */
object DevtoolsFormat {

    // ── snapshot ─────────────────────────────────────────────────────────

    /**
     * One line per element: `<indent><ref> <role> "<name>" …`. Example:
     * ```
     * e1 navigation "Main"
     *   e2 link "Docs"
     * e3 textbox "Email" value="a@b.c" [required]
     * e4 button "Sign in" [disabled]
     * ```
     */
    fun snapshot(json: JSONObject, maxChars: Int = DevtoolsLimits.SNAPSHOT_OUTPUT_CHARS): String {
        val nodes: JSONArray = json.optJSONArray("nodes") ?: JSONArray()
        val total = json.optInt("total", nodes.length())
        val interactive = json.optBoolean("interactive", true)
        val sb = StringBuilder()
        val title = DevtoolsLimits.clip(json.optString("title", ""), 80)
        sb.append("Snapshot: ")
        if (title.isNotEmpty()) sb.append(title).append(" - ")
        sb.append(DevtoolsLimits.clip(json.optString("url", ""), 200)).append('\n')
        if (nodes.length() == 0) {
            sb.append(
                if (interactive) "No interactive elements found. Try interactive=false for headings and text, get_text, or a screenshot."
                else "No visible elements found. The page may still be loading (use wait), or it draws to a canvas (use screenshot).",
            )
            return sb.toString()
        }
        var written = 0
        var cut = false
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            val line = snapshotLine(n)
            if (sb.length + line.length + 1 > maxChars) { cut = true; break }
            sb.append(line).append('\n')
            written++
        }
        val omitted = total - written
        sb.append("[").append(written).append(if (interactive) " interactive element" else " element").append(if (written == 1) "" else "s")
        if (omitted > 0 || cut) sb.append("; ").append(maxOf(omitted, nodes.length() - written)).append(" more not shown - scope with selector, or raise limit")
        sb.append(". Pass ref=<id> to click/type/hover/get_text/select_option/inspect/wait. Refs die on navigation; re-snapshot after the page changes.]")
        return sb.toString()
    }

    internal fun snapshotLine(n: JSONObject): String = buildString {
        val depth = n.optInt("d", 0).coerceIn(0, DevtoolsLimits.SNAPSHOT_MAX_DEPTH)
        repeat(depth) { append("  ") }
        append(DevtoolsLimits.normalizeRef(n.optString("r")) ?: "e?")
        append(' ').append(oneLine(n.optString("ro", "element"), 24))
        val name = oneLine(n.optString("n", ""), 120)
        if (name.isNotEmpty()) append(" \"").append(name.replace("\"", "'")).append('"')
        if (n.has("lv") && n.optInt("lv") > 0) append(" [level=").append(n.optInt("lv")).append(']')
        val type = oneLine(n.optString("ty", ""), 20)
        if (type.isNotEmpty()) append(" type=").append(type)
        if (n.has("v")) append(" value=\"").append(oneLine(n.optString("v"), 60).replace("\"", "'")).append('"')
        if (n.has("o")) append(" (").append(n.optInt("o")).append(" options)")
        val href = oneLine(n.optString("h", ""), 100)
        if (href.isNotEmpty()) append(" -> ").append(href)
        val states = n.optJSONArray("s")
        if (states != null && states.length() > 0) {
            append(" [")
            for (i in 0 until minOf(states.length(), 8)) {
                if (i > 0) append(", ")
                append(oneLine(states.optString(i), 16))
            }
            append(']')
        }
        val hint = oneLine(n.optString("hi", ""), 40)
        if (hint.isNotEmpty()) append(" hint=").append(hint)
    }

    private fun oneLine(s: String, max: Int): String =
        DevtoolsLimits.clip(s.replace('\n', ' ').replace('\r', ' ').trim(), max)

    // ── inspect ──────────────────────────────────────────────────────────

    fun inspect(json: JSONObject, target: String): String = buildString {
        val count = json.optInt("count", 0)
        val arr = json.optJSONArray("inspected") ?: JSONArray()
        if (count == 0 || arr.length() == 0) {
            append("No element matches $target (count 0).")
            return@buildString
        }
        appendLine("$target: $count match${if (count == 1) "" else "es"}${if (arr.length() < count) ", showing first ${arr.length()}" else ""}")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (arr.length() > 1) appendLine("--- [$i] ---")
            val attrs = o.optJSONObject("attrs")
            append("<").append(o.optString("tag", "?")).append(">")
            appendLine("  children: ${o.optInt("children", 0)}")
            val r = o.optJSONObject("rect")
            if (r != null) {
                appendLine(
                    "rect: x=${r.optInt("x")} y=${r.optInt("y")} ${r.optInt("w")}x${r.optInt("h")} (page ${r.optInt("pageX")},${r.optInt("pageY")})",
                )
            }
            append("visible: ").append(o.optBoolean("visible", false))
            val why = o.optString("why", "")
            if (why.isNotEmpty()) append(" (").append(why).append(')')
            append(", in viewport: ").append(o.optBoolean("inViewport", false))
            val covered = o.optString("coveredBy", "")
            if (covered.isNotEmpty()) append(", covered at its centre by ").append(covered)
            if (o.optString("pointerEvents") == "none") append(", pointer-events:none")
            if (o.optBoolean("disabled", false)) append(", disabled")
            appendLine()
            if (o.has("value")) appendLine("value: ${o.optString("value")}")
            if (o.has("checked")) appendLine("checked: ${o.optBoolean("checked")}")
            if (attrs != null && attrs.length() > 0) {
                appendLine("attributes:")
                val keys = attrs.keys()
                while (keys.hasNext()) { val k = keys.next(); appendLine("  $k=\"${attrs.optString(k)}\"") }
            }
            val text = o.optString("text", "")
            if (text.isNotEmpty()) appendLine("text: $text")
            val styles = o.optJSONObject("styles")
            if (styles != null && styles.length() > 0) {
                appendLine("computed style:")
                val keys = styles.keys()
                while (keys.hasNext()) { val k = keys.next(); appendLine("  $k: ${styles.optString(k)}") }
            }
            val html = o.optString("html", "")
            if (html.isNotEmpty()) {
                val len = o.optInt("htmlLen", html.length)
                appendLine("outerHTML${if (len > html.length) " (first ${html.length} of $len chars)" else ""}:")
                appendLine(html)
            }
        }
    }.trimEnd().let { DevtoolsLimits.capOutput(it, DevtoolsLimits.INSPECT_OUTPUT_CHARS) }

    // ── storage ──────────────────────────────────────────────────────────

    /** `name=value; name2=value2` (a Cookie header, or document.cookie) → pairs, in order. */
    fun parseCookieHeader(header: String?): List<Pair<String, String>> {
        if (header.isNullOrBlank()) return emptyList()
        return header.split(';').mapNotNull { part ->
            val t = part.trim()
            if (t.isEmpty()) return@mapNotNull null
            val eq = t.indexOf('=')
            if (eq < 0) t to "" else if (eq == 0) null else t.substring(0, eq).trim() to t.substring(eq + 1).trim()
        }
    }

    /**
     * [pageJson] is the storage script's result for the tab's current
     * document; [nativeCookieHeader] is CookieManager's cookie string for
     * [pageUrl]. A cookie the native store sends but `document.cookie` does
     * not show is HttpOnly (or scoped to a different Path) - CookieManager
     * does not expose the attribute itself, so that is how it is marked.
     * [keywords], when given, keep only keys containing any of them.
     */
    fun storage(pageJson: JSONObject, nativeCookieHeader: String?, pageUrl: String, keywords: List<String>?): String = buildString {
        val keep: (String) -> Boolean = { name ->
            keywords.isNullOrEmpty() || keywords.any { name.contains(it, ignoreCase = true) }
        }
        val valueCap = DevtoolsLimits.STORAGE_VALUE_CHARS
        appendLine("Storage for ${DevtoolsLimits.clip(pageUrl, 200)}")
        val jsCookies = parseCookieHeader(pageJson.optString("cookie", ""))
        val jsNames = jsCookies.map { it.first }.toSet()
        val native = parseCookieHeader(nativeCookieHeader)
        val all = LinkedHashMap<String, Pair<String, Boolean>>() // name -> (value, visibleToJs)
        for ((n, v) in native) all[n] = v to (n in jsNames)
        for ((n, v) in jsCookies) if (n !in all) all[n] = v to true
        val shownCookies = all.filterKeys(keep)
        appendLine("Cookies (${shownCookies.size}${if (shownCookies.size != all.size) " of ${all.size}" else ""}):")
        if (pageJson.has("cookieErr")) appendLine("  (document.cookie unreadable: ${DevtoolsLimits.clip(pageJson.optString("cookieErr"), 120)})")
        for ((n, pair) in shownCookies) {
            append("  ").append(DevtoolsLimits.clip(n, 80)).append(" = ").append(DevtoolsLimits.clip(pair.first, valueCap))
            if (pair.first.length > valueCap) append(" (").append(pair.first.length).append(" chars)")
            if (!pair.second) append("  [HttpOnly or path-scoped: not visible to page JS]")
            appendLine()
        }
        if (shownCookies.isEmpty()) appendLine("  (none)")
        for ((label, key) in listOf("localStorage" to "ls", "sessionStorage" to "ss")) {
            val err = pageJson.optString("${key}Err", "")
            val arr = pageJson.optJSONArray(key) ?: JSONArray()
            val totalKeys = pageJson.optInt("${key}N", arr.length())
            val rows = (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.filter { keep(it.optString(0)) }
            appendLine("$label (${rows.size}${if (rows.size != totalKeys) " of $totalKeys" else ""} keys):")
            if (err.isNotEmpty()) appendLine("  (unavailable: ${DevtoolsLimits.clip(err, 120)})")
            for (row in rows) {
                val v = row.optString(1)
                val fullLen = row.optInt(2, v.length)
                append("  ").append(DevtoolsLimits.clip(row.optString(0), 120)).append(" = ").append(DevtoolsLimits.clip(v, valueCap))
                if (fullLen > valueCap) append(" (").append(fullLen).append(" chars)")
                appendLine()
            }
            if (rows.isEmpty() && err.isEmpty()) appendLine("  (none)")
        }
    }.trimEnd().let { DevtoolsLimits.capOutput(it, DevtoolsLimits.STORAGE_OUTPUT_CHARS) }

    // ── performance ──────────────────────────────────────────────────────

    fun performance(json: JSONObject): String = buildString {
        appendLine("Performance for ${DevtoolsLimits.clip(json.optString("url"), 200)}")
        val nav = json.optJSONObject("nav")
        if (nav != null) {
            append("Navigation (${nav.optString("type", "navigate")}")
            if (nav.optInt("status") > 0) append(", HTTP ${nav.optInt("status")}")
            if (nav.optInt("redirects") > 0) append(", ${nav.optInt("redirects")} redirects")
            appendLine("):")
            appendLine("  TTFB: ${nav.optInt("ttfb")}ms   response end: ${nav.optInt("responseEnd")}ms   DOM interactive: ${nav.optInt("domInteractive")}ms")
            appendLine(
                "  DOMContentLoaded: ${ms(nav.optInt("dcl"))}   load: ${ms(nav.optInt("load"))}   " +
                    "document: ${DevtoolsLimits.bytes(nav.optLong("transfer"))} transferred, ${DevtoolsLimits.bytes(nav.optLong("decoded"))} decoded",
            )
        } else {
            appendLine("Navigation timing: not available")
        }
        append("Paint: first paint ${if (json.has("fp")) "${json.optInt("fp")}ms" else "n/a"}, FCP ${if (json.has("fcp")) "${json.optInt("fcp")}ms" else "n/a"}")
        append(", LCP ${if (json.has("lcp")) "${json.optInt("lcp")}ms" else "n/a (not observed)"}")
        if (json.has("cls")) append(", CLS ${json.optDouble("cls")}")
        appendLine()
        val res = json.optJSONObject("res")
        if (res != null) {
            appendLine("Resources: ${res.optInt("count")} requests, ${DevtoolsLimits.bytes(res.optLong("transfer"))} transferred (cross-origin sizes may read 0)")
            val byType = res.optJSONObject("byType")
            if (byType != null && byType.length() > 0) {
                val parts = ArrayList<String>()
                val keys = byType.keys()
                while (keys.hasNext() && parts.size < 12) {
                    val k = keys.next()
                    val pair = byType.optJSONArray(k)
                    parts.add("${DevtoolsLimits.clip(k, 16)} ${pair?.optInt(0) ?: 0} (${DevtoolsLimits.bytes(pair?.optLong(1) ?: 0L)})")
                }
                appendLine("  by type: ${parts.joinToString(", ")}")
            }
            val slow = res.optJSONArray("slowest")
            if (slow != null && slow.length() > 0) {
                appendLine("  slowest:")
                for (i in 0 until minOf(slow.length(), 5)) {
                    val row = slow.optJSONArray(i) ?: continue
                    appendLine("    ${row.optInt(0)}ms ${DevtoolsLimits.clip(row.optString(1), 120)}")
                }
            }
        }
        val mem = json.optJSONObject("mem")
        if (mem != null) {
            appendLine(
                "JS heap: ${DevtoolsLimits.bytes(mem.optLong("used"))} used of ${DevtoolsLimits.bytes(mem.optLong("total"))} " +
                    "(limit ${DevtoolsLimits.bytes(mem.optLong("limit"))})",
            )
        } else {
            appendLine("JS heap: not exposed by this WebView")
        }
        if (json.has("domNodes")) appendLine("DOM elements: ${json.optInt("domNodes")}")
        if (json.has("now")) append("Page age: ${json.optInt("now")}ms")
    }.trimEnd().let { DevtoolsLimits.capOutput(it, DevtoolsLimits.PERFORMANCE_OUTPUT_CHARS) }

    private fun ms(v: Int): String = if (v > 0) "${v}ms" else "not fired yet"

    // ── evaluate ─────────────────────────────────────────────────────────

    data class EvalOutcome(val text: String, val success: Boolean)

    /** [raw] is what the evaluate wrapper sent over the bridge. */
    fun evaluate(raw: String?, ranAsExpression: Boolean): EvalOutcome {
        if (raw == null) return EvalOutcome("Error: evaluate returned nothing", false)
        val json = try { JSONObject(raw) } catch (_: Exception) {
            return EvalOutcome(DevtoolsLimits.capOutput(raw, DevtoolsLimits.EVAL_OUTPUT_CHARS), true)
        }
        if (json.has("error")) {
            val stack = json.optString("stack", "").trim()
            val msg = json.optString("error")
            val text = buildString {
                append("Error: Script threw: ").append(DevtoolsLimits.clip(msg, 600))
                // V8 stacks start with the message; keep only the frames.
                val frames = stack.lines().map { it.trim() }.filter { it.startsWith("at ") }
                if (frames.isNotEmpty()) {
                    append('\n')
                    append(DevtoolsLimits.clip(frames.take(8).joinToString("\n") { "    $it" }, DevtoolsLimits.EVAL_STACK_CHARS))
                }
            }
            return EvalOutcome(text, false)
        }
        if (json.optBoolean("truncated", false)) {
            return EvalOutcome(
                DevtoolsLimits.capOutput(
                    "(${json.optString("type", "value")}, result too large - showing the start of its JSON)\n" + json.optString("text"),
                    DevtoolsLimits.EVAL_OUTPUT_CHARS,
                ),
                true,
            )
        }
        val type = json.optString("type", "")
        val value: Any? = json.opt("value")
        val body: String = when {
            type == "undefined" ->
                if (ranAsExpression) "undefined"
                else "undefined (the script ran as statements and returned nothing - end it with `return <value>` to get a result)"
            value == null || value == JSONObject.NULL -> "null"
            value is String -> value
            value is JSONObject -> value.toString()
            value is JSONArray -> value.toString()
            else -> value.toString()
        }
        return EvalOutcome(DevtoolsLimits.capOutput(body, DevtoolsLimits.EVAL_OUTPUT_CHARS), true)
    }
}

/**
 * What the `wait` action waits for, and the pure decision whether one probe
 * of the page ([DevtoolsJS.waitProbe]) satisfies it. All given conditions
 * must hold at once.
 */
data class WaitSpec(
    val selector: String? = null,
    val ref: String? = null,
    /** visible | hidden | attached | detached */
    val state: String = "visible",
    val text: String? = null,
    val urlContains: String? = null,
    val networkIdle: Boolean = false,
) {
    val hasTarget: Boolean get() = selector != null || ref != null
    val isEmpty: Boolean get() = !hasTarget && text == null && urlContains == null && !networkIdle

    data class Check(val satisfied: Boolean, val pending: String, val fatal: String? = null)

    /**
     * @param probe the probe script's JSON
     * @param nativeQuietMs ms since the WebView last started or failed a
     *   request as seen natively, or -1 when unknown
     */
    fun check(probe: JSONObject, nativeQuietMs: Long): Check {
        if (probe.has("error")) return Check(false, probe.optString("error"))
        val err = probe.optString("err", "")
        if (err.startsWith("Invalid selector")) return Check(false, err, fatal = err)
        val pending = ArrayList<String>()
        if (hasTarget) {
            val attached = probe.optBoolean("attached", false)
            val visible = probe.optBoolean("visible", false)
            val ok = when (state) {
                "hidden" -> !visible
                "attached" -> attached
                "detached" -> !attached
                else -> visible
            }
            if (!ok) pending.add("${ref?.let { "ref $it" } ?: selector} is not $state (attached=$attached, visible=$visible)")
        }
        if (text != null && !probe.optBoolean("text", false)) pending.add("text ${JSONObject.quote(DevtoolsLimits.clip(text, 60))} not on the page")
        if (urlContains != null && !probe.optString("url", "").contains(urlContains)) {
            pending.add("URL ${DevtoolsLimits.clip(probe.optString("url", ""), 120)} does not contain ${JSONObject.quote(urlContains)}")
        }
        if (networkIdle) {
            val inflight = probe.optInt("inflight", 0)
            val pageQuiet = probe.optLong("quiet", -1L)
            val quiet = when {
                pageQuiet < 0 -> nativeQuietMs
                nativeQuietMs < 0 -> pageQuiet
                else -> minOf(pageQuiet, nativeQuietMs)
            }
            val ready = probe.optString("rs") == "complete"
            if (!ready) pending.add("document is still ${probe.optString("rs", "loading")}")
            else if (inflight > 0) pending.add("$inflight fetch/XHR request(s) in flight")
            else if (quiet in 0 until DevtoolsLimits.NETWORK_IDLE_QUIET_MS) pending.add("network active ${quiet}ms ago")
        }
        return Check(pending.isEmpty(), pending.joinToString("; "))
    }

    fun describe(): String {
        val parts = ArrayList<String>()
        if (hasTarget) parts.add("${ref?.let { "ref $it" } ?: selector} $state")
        if (text != null) parts.add("text ${JSONObject.quote(DevtoolsLimits.clip(text, 60))}")
        if (urlContains != null) parts.add("URL contains ${JSONObject.quote(urlContains)}")
        if (networkIdle) parts.add("network idle")
        return parts.joinToString(" + ")
    }

    companion object {
        val STATES: List<String> = listOf("visible", "hidden", "attached", "detached")

        fun normalizeState(raw: String?): String? {
            val s = raw?.trim()?.lowercase()
            if (s.isNullOrEmpty()) return "visible"
            return if (s in STATES) s else null
        }
    }
}
