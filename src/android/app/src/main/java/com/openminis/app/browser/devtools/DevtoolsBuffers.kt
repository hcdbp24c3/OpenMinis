package com.openminis.app.browser.devtools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [M5-browser-devtools] Per-tab console and network logs: bounded ring
 * buffers, the merge rules that fold several observations of one request into
 * one row, and the text the model reads. Pure Kotlin (org.json only) - see
 * DevtoolsBuffersTest.
 *
 * Both logs are written from several threads (WebView UI thread, the
 * JavaBridge thread, the request-intercept IO thread), so every public
 * method is synchronized.
 */

// ── Console ──────────────────────────────────────────────────────────────

data class ConsoleEntry(
    val seq: Int,
    /** debug | log | info | warn | error | nav */
    val level: String,
    /** console | exception | rejection | nav */
    val kind: String,
    val message: String,
    val source: String = "",
    val line: Int = 0,
    val column: Int = 0,
    val stack: String = "",
)

class ConsoleLog(private val capacity: Int = DevtoolsLimits.CONSOLE_RING) {
    private val entries = ArrayDeque<ConsoleEntry>()
    private var lastSeq = 0

    /** Highest sequence number handed out so far - the cursor for `since`. */
    val cursor: Int @Synchronized get() = lastSeq

    val size: Int @Synchronized get() = entries.size

    /**
     * Adds one entry, clipping every field. An uncaught error reaches us
     * twice - once from WebChromeClient.onConsoleMessage ("Uncaught …", with
     * the location but no stack) and once from the injected error listener
     * (with the stack) - in either order. The two are folded into one row
     * that keeps the first sequence number and the richer fields.
     */
    @Synchronized
    fun add(
        level: String,
        kind: String,
        message: String?,
        source: String? = null,
        line: Int = 0,
        column: Int = 0,
        stack: String? = null,
    ): ConsoleEntry {
        val lv = normalizeLevel(level)
        val msg = DevtoolsLimits.clip(message, DevtoolsLimits.CONSOLE_MESSAGE_CHARS)
        val src = DevtoolsLimits.clip(source, 300)
        val st = DevtoolsLimits.clip(stack, DevtoolsLimits.CONSOLE_STACK_CHARS)
        val k = when (kind) { "exception", "rejection", "nav" -> kind; else -> "console" }

        if (lv == "error" && k != "nav") {
            val twinIndex = findTwin(k, msg)
            if (twinIndex >= 0) {
                val old = entries[twinIndex]
                val merged = old.copy(
                    kind = if (old.kind == "console") k else old.kind,
                    message = if (old.kind == "console") old.message else msg.ifEmpty { old.message },
                    source = old.source.ifEmpty { src },
                    line = if (old.line > 0) old.line else line,
                    column = if (old.column > 0) old.column else column,
                    stack = old.stack.ifEmpty { st },
                )
                entries[twinIndex] = merged
                return merged
            }
        }

        lastSeq += 1
        val entry = ConsoleEntry(lastSeq, lv, k, msg, src, maxOf(0, line), maxOf(0, column), st)
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
        return entry
    }

    private fun findTwin(kind: String, message: String): Int {
        val core = uncaughtCore(message)
        if (core.isEmpty()) return -1
        val from = maxOf(0, entries.size - TWIN_WINDOW)
        for (i in entries.size - 1 downTo from) {
            val e = entries[i]
            if (e.level != "error" || e.kind == "nav") continue
            // One side must be the native console line, the other the hook's.
            val pair = (kind == "console") != (e.kind == "console")
            if (!pair) continue
            val native = if (kind == "console") message else e.message
            if (!native.startsWith("Uncaught")) continue
            val other = uncaughtCore(e.message)
            if (other.isEmpty()) continue
            if (core == other || core.contains(other) || other.contains(core)) return i
        }
        return -1
    }

    @Synchronized
    fun clear() { entries.clear() }

    /**
     * Entries newer than [since], at or above [minLevel], containing
     * [filter]; the newest [limit] of those. `nav` markers always pass the
     * level filter so the reader can tell which page an error belongs to.
     */
    @Synchronized
    fun read(since: Int = 0, minLevel: String? = null, filter: String? = null, limit: Int = DevtoolsLimits.CONSOLE_DEFAULT_SHOWN): ConsoleRead {
        val floor = severity(minLevel)
        val needle = filter?.trim()?.takeIf { it.isNotEmpty() }
        val matched = entries.filter { e ->
            e.seq > since &&
                (e.kind == "nav" || severity(e.level) >= floor) &&
                (needle == null || e.message.contains(needle, ignoreCase = true) || e.source.contains(needle, ignoreCase = true))
        }
        val cap = limit.coerceIn(1, capacity)
        return ConsoleRead(matched.takeLast(cap), matched.size, entries.size, lastSeq)
    }

    companion object {
        private const val TWIN_WINDOW = 12

        fun normalizeLevel(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "error", "err", "severe" -> "error"
            "warn", "warning" -> "warn"
            "info" -> "info"
            "debug", "verbose", "tip" -> "debug"
            "nav" -> "nav"
            else -> "log"
        }

        /** 0 debug … 3 error; an unknown or "all" filter is 0 (everything). */
        fun severity(level: String?): Int = when (level?.trim()?.lowercase()) {
            "error", "err", "errors" -> 3
            "warn", "warning", "warnings" -> 2
            "info", "log" -> 1
            else -> 0
        }

        /** The part of an uncaught-error line both reporters agree on. */
        internal fun uncaughtCore(message: String): String {
            var s = message.trim()
            for (p in listOf("Unhandled promise rejection:", "Uncaught (in promise)", "Uncaught")) {
                if (s.startsWith(p)) { s = s.substring(p.length).trim(); break }
            }
            return s.trimEnd('…').take(160)
        }
    }
}

data class ConsoleRead(val shown: List<ConsoleEntry>, val matched: Int, val buffered: Int, val cursor: Int)

object ConsoleFormatter {
    fun format(read: ConsoleRead, cleared: Boolean = false): String = buildString {
        if (read.shown.isEmpty()) {
            append("Console: no matching entries (${read.buffered} buffered, cursor ${read.cursor}).")
            if (cleared) append(" Buffer cleared.")
            return@buildString
        }
        append("Console: ${read.shown.size} of ${read.matched} matching (${read.buffered} buffered). ")
        appendLine("cursor=${read.cursor} - pass since=${read.cursor} to read only newer entries.")
        for (e in read.shown) {
            if (e.kind == "nav") {
                appendLine("#${e.seq} ---- page: ${e.message}")
                continue
            }
            append("#${e.seq} ${e.level}")
            if (e.kind != "console") append("(${e.kind})")
            append(' ').append(e.message)
            if (e.source.isNotEmpty()) {
                append("  @ ").append(e.source)
                if (e.line > 0) append(':').append(e.line)
                if (e.column > 0) append(':').append(e.column)
            }
            appendLine()
            if (e.stack.isNotEmpty()) {
                // The first stack line usually repeats the message.
                val lines = e.stack.lines().map { it.trim() }.filter { it.isNotEmpty() && !e.message.contains(it) }
                for (l in lines.take(6)) appendLine("    $l")
                if (lines.size > 6) appendLine("    … ${lines.size - 6} more frames")
            }
        }
        if (cleared) append("Buffer cleared.")
    }.trimEnd().let { DevtoolsLimits.capOutput(it, 12_000) }
}

// ── Network ──────────────────────────────────────────────────────────────

data class NetEntry(
    val seq: Int,
    val url: String,
    val method: String = "",
    /** document | fetch | xhr | script | img | css | font | … | other */
    val type: String = "",
    val status: Int = 0,
    val mime: String = "",
    val durationMs: Int = -1,
    val transferSize: Long = 0,
    val encodedSize: Long = 0,
    val decodedSize: Long = 0,
    val failed: Boolean = false,
    val error: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val bodyPreview: String = "",
    val bodyTruncated: Boolean = false,
    val note: String = "",
    /** Which observers contributed: req (native request), rt (resource timing), js (fetch/XHR wrapper), err (native error). */
    val sources: Set<String> = emptySet(),
) {
    val isProblem: Boolean get() = failed || status >= 400
}

/** One observation of a request, before it is merged into the log. */
data class NetEvent(
    val source: String,
    val url: String,
    val method: String = "",
    val type: String = "",
    val status: Int = 0,
    val mime: String = "",
    val durationMs: Int = -1,
    val transferSize: Long = 0,
    val encodedSize: Long = 0,
    val decodedSize: Long = 0,
    val failed: Boolean = false,
    val error: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val bodyPreview: String = "",
    val bodyTruncated: Boolean = false,
    val note: String = "",
)

class NetworkLog(private val capacity: Int = DevtoolsLimits.NETWORK_RING) {
    private val entries = ArrayDeque<NetEntry>()
    private var lastSeq = 0

    val cursor: Int @Synchronized get() = lastSeq
    val size: Int @Synchronized get() = entries.size

    /**
     * Records one observation. The same request is seen by up to four
     * observers (see [NetEntry.sources]); an event is merged into the oldest
     * recent row with the same URL (fragment ignored) and a compatible method
     * that this observer has not contributed to yet, otherwise it starts a
     * new row.
     */
    @Synchronized
    fun record(raw: NetEvent): NetEntry {
        val ev = sanitize(raw)
        val key = urlKey(ev.url)
        val from = maxOf(0, entries.size - DevtoolsLimits.NETWORK_MERGE_WINDOW)
        for (i in from until entries.size) {
            val e = entries[i]
            if (ev.source in e.sources) continue
            if (urlKey(e.url) != key) continue
            if (e.method.isNotEmpty() && ev.method.isNotEmpty() && e.method != ev.method) continue
            val merged = merge(e, ev)
            entries[i] = merged
            return merged
        }
        lastSeq += 1
        val entry = merge(NetEntry(seq = lastSeq, url = ev.url), ev)
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
        return entry
    }

    @Synchronized
    fun clear() { entries.clear() }

    @Synchronized
    fun find(seq: Int): NetEntry? = entries.firstOrNull { it.seq == seq }

    @Synchronized
    fun read(since: Int = 0, filter: String? = null, problemsOnly: Boolean = false, limit: Int = DevtoolsLimits.NETWORK_DEFAULT_SHOWN): NetworkRead {
        val needle = filter?.trim()?.takeIf { it.isNotEmpty() }
        val matched = entries.filter { e ->
            e.seq > since &&
                (!problemsOnly || e.isProblem) &&
                (needle == null || e.url.contains(needle, ignoreCase = true) ||
                    e.type.equals(needle, ignoreCase = true) || e.method.equals(needle, ignoreCase = true))
        }
        val cap = limit.coerceIn(1, capacity)
        return NetworkRead(matched.takeLast(cap), matched.size, entries.size, lastSeq)
    }

    companion object {
        fun urlKey(url: String): String = url.substringBefore('#')

        private fun sanitize(ev: NetEvent): NetEvent = ev.copy(
            source = when (ev.source) { "req", "rt", "js", "err" -> ev.source; else -> "js" },
            url = DevtoolsLimits.clip(ev.url, DevtoolsLimits.NETWORK_URL_CHARS),
            method = ev.method.trim().uppercase().take(10),
            type = normalizeType(ev.type),
            status = ev.status.coerceIn(0, 999),
            mime = ev.mime.substringBefore(';').trim().lowercase().take(80),
            durationMs = ev.durationMs.coerceIn(-1, 3_600_000),
            transferSize = maxOf(0L, ev.transferSize),
            encodedSize = maxOf(0L, ev.encodedSize),
            decodedSize = maxOf(0L, ev.decodedSize),
            error = DevtoolsLimits.clip(ev.error, 200),
            requestHeaders = clipHeaders(ev.requestHeaders),
            responseHeaders = clipHeaders(ev.responseHeaders),
            bodyPreview = DevtoolsLimits.clip(ev.bodyPreview, DevtoolsLimits.NETWORK_BODY_PREVIEW_CHARS),
            bodyTruncated = ev.bodyTruncated || ev.bodyPreview.length > DevtoolsLimits.NETWORK_BODY_PREVIEW_CHARS,
            note = DevtoolsLimits.clip(ev.note, 80),
        )

        private fun clipHeaders(h: Map<String, String>): Map<String, String> {
            if (h.isEmpty()) return h
            val out = LinkedHashMap<String, String>()
            for ((k, v) in h) {
                if (out.size >= DevtoolsLimits.NETWORK_HEADERS) break
                out[DevtoolsLimits.clip(k.lowercase(), 60)] = DevtoolsLimits.clip(v, DevtoolsLimits.NETWORK_HEADER_VALUE_CHARS)
            }
            return out
        }

        /** Resource-timing initiator names and our own guesses, as one vocabulary. */
        fun normalizeType(raw: String): String = when (val t = raw.trim().lowercase()) {
            "" -> ""
            "navigation", "document", "iframe", "frame" -> if (t == "iframe" || t == "frame") "iframe" else "document"
            "xmlhttprequest", "xhr" -> "xhr"
            "fetch" -> "fetch"
            "link", "css", "stylesheet" -> "css"
            "img", "image", "imageset", "input" -> "img"
            "script" -> "script"
            "font" -> "font"
            "audio", "video", "track", "media" -> "media"
            "beacon", "ping" -> "beacon"
            else -> t.take(16)
        }

        /** Higher wins when two observers disagree about the resource type. */
        private fun typeRank(type: String, source: String): Int = when {
            type.isEmpty() -> 0
            type == "document" -> 5
            source == "js" -> 4
            source == "rt" && type != "other" -> 3
            type == "other" -> 1
            else -> 2
        }

        private fun merge(e: NetEntry, ev: NetEvent): NetEntry {
            // What the row's current type was ranked as: recomputed from its sources.
            val currentRank = when {
                e.type.isEmpty() -> 0
                e.type == "document" -> 5
                "js" in e.sources && (e.type == "fetch" || e.type == "xhr") -> 4
                "rt" in e.sources && e.type != "other" -> 3
                e.type == "other" -> 1
                else -> 2
            }
            val type = if (typeRank(ev.type, ev.source) > currentRank) ev.type else e.type
            return e.copy(
                method = e.method.ifEmpty { ev.method },
                type = type,
                status = if (ev.status > 0) ev.status else e.status,
                mime = ev.mime.ifEmpty { e.mime },
                durationMs = if (ev.durationMs >= 0 && (e.durationMs < 0 || ev.source == "rt")) ev.durationMs else e.durationMs,
                transferSize = maxOf(e.transferSize, ev.transferSize),
                encodedSize = maxOf(e.encodedSize, ev.encodedSize),
                decodedSize = maxOf(e.decodedSize, ev.decodedSize),
                failed = e.failed || ev.failed,
                error = e.error.ifEmpty { ev.error },
                requestHeaders = if (ev.source == "js" && ev.requestHeaders.isNotEmpty()) e.requestHeaders + ev.requestHeaders
                    else if (e.requestHeaders.isEmpty()) ev.requestHeaders else e.requestHeaders,
                responseHeaders = if (ev.responseHeaders.isNotEmpty()) ev.responseHeaders else e.responseHeaders,
                bodyPreview = e.bodyPreview.ifEmpty { ev.bodyPreview },
                bodyTruncated = e.bodyTruncated || ev.bodyTruncated,
                note = e.note.ifEmpty { ev.note },
                sources = e.sources + ev.source,
            )
        }

        /**
         * Best guess at the resource type from what WebResourceRequest
         * exposes (it has no "destination" field): main frame, the Accept
         * header, then the file extension.
         */
        fun guessType(isMainFrame: Boolean, accept: String?, url: String): String {
            if (isMainFrame) return "document"
            val a = accept?.lowercase().orEmpty()
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
            return when {
                a.startsWith("text/css") -> "css"
                a.startsWith("image/") -> "img"
                a.startsWith("text/html") -> "iframe"
                ext in setOf("js", "mjs") -> "script"
                ext == "css" -> "css"
                ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp") -> "img"
                ext in setOf("woff", "woff2", "ttf", "otf", "eot") -> "font"
                ext in setOf("mp4", "webm", "mp3", "ogg", "wav", "m3u8", "ts", "m4a") -> "media"
                a.contains("application/json") -> "fetch"
                else -> "other"
            }
        }
    }
}

data class NetworkRead(val shown: List<NetEntry>, val matched: Int, val buffered: Int, val cursor: Int)

object NetworkFormatter {
    private const val LIST_URL_CHARS = 140

    fun formatList(read: NetworkRead, deepCapture: Boolean, deepJustEnabled: Boolean = false, cleared: Boolean = false): String = buildString {
        if (read.shown.isEmpty()) {
            append("Network: no matching requests (${read.buffered} buffered, cursor ${read.cursor}).")
        } else {
            append("Network: ${read.shown.size} of ${read.matched} matching (${read.buffered} buffered). ")
            appendLine("cursor=${read.cursor} - pass since=${read.cursor} for newer; request_id=<#> for headers/body.")
            for (e in read.shown) appendLine(line(e))
        }
        if (cleared) { appendLine(); append("Buffer cleared.") }
        if (deepJustEnabled) {
            appendLine()
            append(
                "fetch/XHR detail capture (request+response headers, text/JSON body preview) is now ON for this tab. " +
                    "Requests made from now on carry it - repeat the interaction (or reload), then call network again.",
            )
        } else if (!deepCapture) {
            appendLine()
            append("fetch/XHR detail capture is off for this tab.")
        }
    }.trimEnd().let { DevtoolsLimits.capOutput(it, 12_000) }

    fun line(e: NetEntry): String = buildString {
        append('#').append(e.seq).append(' ')
        append(
            when {
                e.failed && e.status == 0 -> "FAIL"
                e.status > 0 -> e.status.toString()
                else -> "-"
            },
        )
        append(' ').append(e.method.ifEmpty { "GET?" })
        append(' ').append(e.type.ifEmpty { "other" })
        if (e.mime.isNotEmpty()) append(' ').append(e.mime)
        append(' ').append(DevtoolsLimits.bytes(if (e.transferSize > 0) e.transferSize else e.encodedSize))
        append(' ').append(if (e.durationMs >= 0) "${e.durationMs}ms" else "-")
        append(' ').append(DevtoolsLimits.clip(e.url, LIST_URL_CHARS))
        if (e.error.isNotEmpty()) append("  (").append(e.error).append(')')
        if (e.bodyPreview.isNotEmpty()) append("  [body]")
    }

    fun formatDetail(e: NetEntry): String = buildString {
        appendLine("Request #${e.seq}")
        appendLine("URL: ${e.url}")
        appendLine("Method: ${e.method.ifEmpty { "(unknown, probably GET)" }}   Type: ${e.type.ifEmpty { "other" }}")
        append("Status: ")
        appendLine(
            when {
                e.failed && e.status == 0 -> "FAILED${if (e.error.isNotEmpty()) " - ${e.error}" else ""}"
                e.status > 0 -> "${e.status}${if (e.error.isNotEmpty()) " - ${e.error}" else ""}"
                else -> "unknown (not exposed for this request)"
            },
        )
        if (e.mime.isNotEmpty()) appendLine("MIME: ${e.mime}")
        if (e.durationMs >= 0) appendLine("Duration: ${e.durationMs}ms")
        if (e.transferSize > 0 || e.encodedSize > 0 || e.decodedSize > 0) {
            appendLine(
                "Size: transfer ${DevtoolsLimits.bytes(e.transferSize)}, encoded ${DevtoolsLimits.bytes(e.encodedSize)}, " +
                    "decoded ${DevtoolsLimits.bytes(e.decodedSize)}",
            )
        } else {
            appendLine("Size: unknown (cross-origin without Timing-Allow-Origin, cached, or not measured)")
        }
        if (e.note.isNotEmpty()) appendLine("Note: ${e.note}")
        appendLine("Seen by: ${e.sources.sorted().joinToString(", ")}")
        if (e.requestHeaders.isNotEmpty()) {
            appendLine("Request headers:")
            for ((k, v) in e.requestHeaders) appendLine("  $k: $v")
        }
        if (e.responseHeaders.isNotEmpty()) {
            appendLine("Response headers:")
            for ((k, v) in e.responseHeaders) appendLine("  $k: $v")
        }
        if (e.bodyPreview.isNotEmpty()) {
            appendLine("Response body${if (e.bodyTruncated) " (first ${DevtoolsLimits.NETWORK_BODY_PREVIEW_CHARS} chars)" else ""}:")
            append(e.bodyPreview)
        } else {
            append("Response body: not captured (only fetch/XHR text or JSON responses made after detail capture was switched on).")
        }
    }.trimEnd().let { DevtoolsLimits.capOutput(it, 8_000) }
}

// ── Events reported by the injected hook ─────────────────────────────────

/**
 * Parses one batch pushed by the page hook through the JS bridge. The page
 * controls this text, so it is size-checked, every field is read defensively
 * and clipped again when it is recorded. Unknown records are skipped.
 */
object DevtoolsEvents {

    data class Parsed(val console: List<ConsoleInput>, val network: List<NetEvent>)

    data class ConsoleInput(
        val level: String,
        val kind: String,
        val message: String,
        val source: String,
        val line: Int,
        val column: Int,
        val stack: String,
    )

    fun parse(batchJson: String?): Parsed {
        val console = ArrayList<ConsoleInput>()
        val network = ArrayList<NetEvent>()
        if (batchJson == null || batchJson.length > DevtoolsLimits.EVENT_BATCH_CHARS) return Parsed(console, network)
        val arr = try { JSONArray(batchJson) } catch (_: Exception) { return Parsed(console, network) }
        val n = minOf(arr.length(), DevtoolsLimits.EVENT_BATCH_ITEMS)
        for (i in 0 until n) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("k")) {
                "c" -> console.add(
                    ConsoleInput(
                        level = o.optString("lv", "error"),
                        kind = o.optString("kind", "console"),
                        message = str(o, "m"),
                        source = str(o, "u"),
                        line = o.optInt("l", 0),
                        column = o.optInt("c", 0),
                        stack = str(o, "st"),
                    ),
                )
                "n" -> {
                    val url = str(o, "u")
                    if (url.isEmpty()) continue
                    val src = o.optString("src")
                    network.add(
                        NetEvent(
                            source = if (src == "rt") "rt" else "js",
                            url = url,
                            method = str(o, "me"),
                            type = str(o, "ty"),
                            status = o.optInt("s", 0),
                            mime = str(o, "mt"),
                            durationMs = o.optInt("d", -1),
                            transferSize = o.optLong("ts", 0L),
                            encodedSize = o.optLong("es", 0L),
                            decodedSize = o.optLong("ds", 0L),
                            failed = o.optInt("f", 0) == 1 || o.optBoolean("f", false),
                            error = str(o, "er"),
                            requestHeaders = headers(o.optJSONObject("rq")),
                            responseHeaders = headers(o.optJSONObject("rs")),
                            bodyPreview = str(o, "b"),
                            bodyTruncated = o.optInt("bt", 0) == 1,
                            note = str(o, "note"),
                        ),
                    )
                }
            }
        }
        return Parsed(console, network)
    }

    private fun str(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        return o.opt(key)?.toString().orEmpty()
    }

    private fun headers(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext() && out.size < DevtoolsLimits.NETWORK_HEADERS) {
            val k = keys.next()
            out[k] = o.opt(k)?.toString().orEmpty()
        }
        return out
    }
}
