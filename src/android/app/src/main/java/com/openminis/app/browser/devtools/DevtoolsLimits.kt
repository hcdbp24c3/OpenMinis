package com.openminis.app.browser.devtools

/**
 * [M5-browser-devtools] Every bound the devtools actions apply, in one place,
 * plus the small pure helpers that enforce them. Nothing here touches
 * Android, so it is unit-tested on the JVM (DevtoolsLimitsTest).
 *
 * Page data is untrusted: whatever the injected hook reports is clipped again
 * on the Kotlin side with these limits before it is stored or shown.
 */
object DevtoolsLimits {
    const val CONSOLE_RING = 200
    const val CONSOLE_MESSAGE_CHARS = 500
    const val CONSOLE_STACK_CHARS = 1_500
    const val CONSOLE_DEFAULT_SHOWN = 50

    const val NETWORK_RING = 300
    const val NETWORK_URL_CHARS = 600
    const val NETWORK_BODY_PREVIEW_CHARS = 2_048
    const val NETWORK_HEADERS = 25
    const val NETWORK_HEADER_VALUE_CHARS = 200
    const val NETWORK_DEFAULT_SHOWN = 40
    /** How far back an incoming event may be merged into an existing request. */
    const val NETWORK_MERGE_WINDOW = 80

    /** Largest bridge payload (one batch of hook events) that is parsed at all. */
    const val EVENT_BATCH_CHARS = 400_000
    const val EVENT_BATCH_ITEMS = 100

    const val EVAL_OUTPUT_CHARS = 8_000
    const val EVAL_STACK_CHARS = 1_500
    const val EVAL_DEFAULT_TIMEOUT_MS = 30_000L
    /**
     * Kept well under BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS (75s): an
     * evaluate may spend up to two bounded script launches before this wait.
     */
    const val EVAL_MAX_TIMEOUT_MS = 40_000L

    const val SNAPSHOT_DEFAULT_NODES = 150
    const val SNAPSHOT_MAX_NODES = 400
    const val SNAPSHOT_DEFAULT_DEPTH = 3
    const val SNAPSHOT_MAX_DEPTH = 6
    const val SNAPSHOT_OUTPUT_CHARS = 12_000

    const val INSPECT_DEFAULT_ELEMENTS = 1
    const val INSPECT_MAX_ELEMENTS = 5
    const val INSPECT_HTML_CHARS = 2_000
    const val INSPECT_MAX_PROPERTIES = 30
    const val INSPECT_OUTPUT_CHARS = 8_000

    const val STORAGE_VALUE_CHARS = 200
    const val STORAGE_KEYS_PER_STORE = 100
    const val STORAGE_OUTPUT_CHARS = 6_000

    const val PERFORMANCE_OUTPUT_CHARS = 3_000

    const val WAIT_DEFAULT_TIMEOUT_MS = 10_000L
    /** Well under BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS (75s). */
    const val WAIT_MAX_TIMEOUT_MS = 30_000L
    const val WAIT_MIN_TIMEOUT_MS = 250L
    const val WAIT_POLL_MS = 150L
    /** Network counts as idle after this long without a request starting or finishing. */
    const val NETWORK_IDLE_QUIET_MS = 500L

    /** Cuts [s] to [max] chars, marking the cut. Null becomes "". */
    fun clip(s: String?, max: Int): String {
        if (s == null) return ""
        if (max <= 0) return ""
        return if (s.length <= max) s else s.substring(0, max - 1) + "…"
    }

    /** Caps a finished report, saying how much was dropped. */
    fun capOutput(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.substring(0, max) + "\n… [truncated: ${text.length - max} more chars; narrow the request]"
    }

    /**
     * The `timeout` argument for the new actions. The tool schema documents it
     * in seconds while the shell help documents milliseconds, so a value up to
     * 120 is read as seconds and anything larger as milliseconds. Clamped to
     * [[min], [max]]; absent or non-positive gives [default].
     */
    fun timeoutMs(raw: Int?, default: Long, max: Long, min: Long = WAIT_MIN_TIMEOUT_MS): Long {
        if (raw == null || raw <= 0) return default.coerceIn(min, max)
        val ms: Long = if (raw <= 120) raw.toLong() * 1000L else raw.toLong()
        return ms.coerceIn(min, max)
    }

    private val REF_REGEX = Regex("^@?(e[0-9]{1,7})$")

    /**
     * A snapshot ref as the model may write it ("e12", "@e12", "[ref=e12]",
     * " E12 ") normalised to "e12", or null when it is not a ref. The result
     * only ever contains `e` and digits, so it is safe to embed in JS.
     */
    fun normalizeRef(raw: String?): String? {
        var s = raw?.trim()?.lowercase() ?: return null
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1).trim()
        if (s.startsWith("ref=")) s = s.substring(4).trim()
        return REF_REGEX.matchEntire(s)?.groupValues?.get(1)
    }

    /** "1.2kB"-style size; "-" for unknown (0 or negative). */
    fun bytes(n: Long): String = when {
        n <= 0L -> "-"
        n < 1024L -> "${n}B"
        n < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1fkB", n / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1fMB", n / (1024.0 * 1024.0))
    }
}
