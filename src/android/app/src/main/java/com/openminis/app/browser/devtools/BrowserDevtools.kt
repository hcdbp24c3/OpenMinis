package com.openminis.app.browser.devtools

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.browser.BrowserJSResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [M5-browser-devtools] The devtools side of ONE browser tab: its console and
 * network logs, the page hook, and the snapshot / wait / evaluate / inspect /
 * storage / performance / select_option actions.
 *
 * Owned by [com.openminis.app.browser.BrowserUseManager] (one per WebView), so
 * all state here is per tab and dies with the tab. This class is the only
 * Android-facing part; script building, parsing, merging and formatting live
 * in the pure objects next to it (DevtoolsJS, DevtoolsBuffers, DevtoolsFormat,
 * DevtoolsLimits), which carry the unit tests.
 *
 * How it sees the page, without root and without the Chrome DevTools Protocol:
 *  - WebChromeClient.onConsoleMessage → every console.* call and uncaught
 *    error, with source URL and line;
 *  - WebViewClient.shouldInterceptRequest / onReceivedHttpError /
 *    onReceivedError → every request the WebView makes (URL, method, main
 *    frame or not), HTTP error statuses and network failures;
 *  - a script injected at document start ([DevtoolsJS.hook]) → stacks for
 *    uncaught errors and promise rejections, resource timing, LCP/CLS, and -
 *    once the agent has asked for the network log - fetch/XHR headers and
 *    body previews.
 *
 * Nothing here weakens BrowserActionGuard: every script goes through the
 * manager's bounded evaluator ([evalJs]), and the two waits of its own
 * (evaluate's result, the wait action's polling) are capped below the pool's
 * action deadline.
 *
 * @param evalJs the manager's `evaluateJavascriptValue`: runs a script with
 *   the JS-evaluation timeout and returns its decoded result, or null when it
 *   produced nothing (threw, or did not parse).
 */
class BrowserDevtools(
    private val webView: WebView,
    private val evalJs: suspend (String) -> String?,
) {
    private val consoleLog = ConsoleLog()
    private val networkLog = NetworkLog()

    /** fetch/XHR wrapping is switched on by the first `network` call on this tab. */
    @Volatile
    private var deepCapture: Boolean = false

    /** Wall-clock ms of the last request the WebView started or failed, 0 before the first. */
    private val lastNativeNetActivity = AtomicLong(0L)

    private var documentStartScript: ScriptHandler? = null

    /** evaluate results in flight, keyed by the one-time token embedded in the script. */
    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val bridge = object {
        /** A batch of hook events (JSON array). Runs on the JavaBridge thread. */
        @JavascriptInterface
        fun events(batch: String?) {
            try {
                val parsed = DevtoolsEvents.parse(batch)
                for (c in parsed.console) {
                    consoleLog.add(c.level, c.kind, c.message, c.source, c.line, c.column, c.stack)
                }
                for (n in parsed.network) networkLog.record(n)
            } catch (t: Throwable) {
                Log.w(TAG, "dropping hook events: ${t.message}")
            }
        }

        /** The value of an `evaluate` script. Unknown or already-used tokens are ignored. */
        @JavascriptInterface
        fun result(token: String?, json: String?) {
            if (token == null) return
            val text = json ?: "null"
            pendingResults.remove(token)?.complete(
                if (text.length > MAX_RESULT_CHARS) text.substring(0, MAX_RESULT_CHARS) else text,
            )
        }
    }

    /** Call once from the manager's init, on the UI thread, before the first load. */
    fun install() {
        try {
            webView.addJavascriptInterface(bridge, DevtoolsJS.BRIDGE)
        } catch (t: Throwable) {
            Log.w(TAG, "bridge not installed: ${t.message}")
        }
        registerDocumentStartScript()
    }

    /**
     * (Re)registers the hook to run before any page script in every frame.
     * Needs the DOCUMENT_START_SCRIPT WebView feature; without it the hook is
     * still injected from onPageStarted / onPageFinished and before each
     * action, which misses errors thrown during the first moments of a load.
     */
    private fun registerDocumentStartScript() {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
            documentStartScript?.remove()
            documentStartScript = WebViewCompat.addDocumentStartJavaScript(
                webView, DevtoolsJS.hook(deepCapture), setOf("*"),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "document-start hook unavailable: ${t.message}")
        }
    }

    // ── Hooks called from BrowserUseManager's WebViewClient / WebChromeClient ──

    fun onPageStarted(view: WebView?, url: String?) {
        if (!url.isNullOrEmpty() && url != "about:blank") {
            consoleLog.add("nav", "nav", url)
        }
        injectHook(view)
    }

    fun onPageFinished(view: WebView?) {
        injectHook(view)
    }

    private fun injectHook(view: WebView?) {
        try {
            view?.evaluateJavascript(DevtoolsJS.hook(deepCapture), null)
        } catch (t: Throwable) {
            Log.w(TAG, "hook injection failed: ${t.message}")
        }
    }

    fun onConsoleMessage(message: ConsoleMessage?) {
        if (message == null) return
        try {
            val level = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                ConsoleMessage.MessageLevel.TIP -> "debug"
                else -> "log"
            }
            consoleLog.add(level, "console", message.message(), message.sourceId(), message.lineNumber())
        } catch (t: Throwable) {
            Log.w(TAG, "console message dropped: ${t.message}")
        }
    }

    /** From shouldInterceptRequest - an IO thread. Records only; never alters the request. */
    fun onRequest(request: WebResourceRequest?) {
        try {
            val url = request?.url?.toString() ?: return
            if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) return
            lastNativeNetActivity.set(System.currentTimeMillis())
            val headers: Map<String, String> = request.requestHeaders ?: emptyMap()
            val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
            networkLog.record(
                NetEvent(
                    source = "req",
                    url = url,
                    method = request.method ?: "",
                    type = NetworkLog.guessType(request.isForMainFrame, accept, url),
                    requestHeaders = headers,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "request not recorded: ${t.message}")
        }
    }

    fun onHttpError(request: WebResourceRequest?, response: WebResourceResponse?) {
        try {
            val url = request?.url?.toString() ?: return
            lastNativeNetActivity.set(System.currentTimeMillis())
            networkLog.record(
                NetEvent(
                    source = "err",
                    url = url,
                    method = request.method ?: "",
                    type = if (request.isForMainFrame) "document" else "",
                    status = response?.statusCode ?: 0,
                    mime = response?.mimeType ?: "",
                    error = response?.reasonPhrase ?: "",
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "http error not recorded: ${t.message}")
        }
    }

    fun onRequestFailed(request: WebResourceRequest?, error: WebResourceError?) {
        try {
            val url = request?.url?.toString() ?: return
            lastNativeNetActivity.set(System.currentTimeMillis())
            networkLog.record(
                NetEvent(
                    source = "err",
                    url = url,
                    method = request.method ?: "",
                    type = if (request.isForMainFrame) "document" else "",
                    failed = true,
                    error = error?.description?.toString() ?: "request failed",
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "failure not recorded: ${t.message}")
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────

    /** True for the actions this class implements. */
    fun handles(action: BrowserAction): Boolean = when (action) {
        BrowserAction.SNAPSHOT, BrowserAction.WAIT, BrowserAction.SELECT_OPTION,
        BrowserAction.CONSOLE, BrowserAction.NETWORK, BrowserAction.EVALUATE,
        BrowserAction.INSPECT, BrowserAction.STORAGE, BrowserAction.PERFORMANCE -> true
        else -> false
    }

    suspend fun execute(input: BrowserActionInput): BrowserActionResult {
        if (input.refRaw != null && input.ref == null) {
            return BrowserActionResult.error(
                "'${DevtoolsLimits.clip(input.refRaw, 40)}' is not an element ref. Refs look like e12 and come from the snapshot action.",
            )
        }
        return try {
            when (input.action) {
                BrowserAction.SNAPSHOT -> snapshot(input)
                BrowserAction.WAIT -> waitFor(input)
                BrowserAction.SELECT_OPTION -> selectOption(input)
                BrowserAction.CONSOLE -> console(input)
                BrowserAction.NETWORK -> network(input)
                BrowserAction.EVALUATE -> evaluate(input)
                BrowserAction.INSPECT -> inspect(input)
                BrowserAction.STORAGE -> storage(input)
                BrowserAction.PERFORMANCE -> performance()
                else -> BrowserActionResult.error("${input.action.value} is not a devtools action")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.openminis.app.browser.WebViewWedgedException) {
            // The pool turns this into its "tab stopped responding" handling.
            throw e
        } catch (e: Exception) {
            BrowserActionResult.error("${input.action.value} failed: ${e.message}")
        }
    }

    /** Runs a script that returns a JSON object string; `{error}` and "no result" become errors. */
    private suspend fun runJson(js: String, onJson: (JSONObject) -> BrowserActionResult): BrowserActionResult {
        val decoded = evalJs(js)
        return when (val outcome = BrowserJSResult.classify(decoded)) {
            BrowserJSResult.Outcome.NoResult -> BrowserActionResult.error(BrowserJSResult.NO_RESULT_ERROR)
            is BrowserJSResult.Outcome.ScriptError -> BrowserActionResult.error(outcome.message)
            is BrowserJSResult.Outcome.Json -> onJson(outcome.json)
            is BrowserJSResult.Outcome.Text -> BrowserActionResult.error("Unexpected script result: ${DevtoolsLimits.clip(outcome.text, 200)}")
        }
    }

    /** Asks the page hook to hand over what it has queued, then gives the bridge a moment. */
    private suspend fun flushHook() {
        try {
            evalJs(DevtoolsJS.hook(deepCapture) + "\n" + DevtoolsJS.FLUSH)
            delay(40)
        } catch (e: com.openminis.app.browser.WebViewWedgedException) {
            throw e
        } catch (_: Exception) {
            // A page that cannot run the flush still has its natively captured entries.
        }
    }

    private suspend fun snapshot(input: BrowserActionInput): BrowserActionResult {
        val js = DevtoolsJS.snapshot(
            scopeSelector = input.selector,
            interactiveOnly = input.interactive,
            maxNodes = input.limit ?: DevtoolsLimits.SNAPSHOT_DEFAULT_NODES,
            maxDepth = input.maxDepth ?: DevtoolsLimits.SNAPSHOT_DEFAULT_DEPTH,
            includeUrls = input.urls,
        )
        return runJson(js) { BrowserActionResult(text = DevtoolsFormat.snapshot(it)) }
    }

    private suspend fun inspect(input: BrowserActionInput): BrowserActionResult {
        if (input.ref == null && input.selector == null) {
            return BrowserActionResult.error("inspect requires 'selector' or 'ref'")
        }
        val js = DevtoolsJS.inspect(
            selector = input.selector,
            ref = input.ref,
            properties = input.properties,
            limit = input.limit ?: DevtoolsLimits.INSPECT_DEFAULT_ELEMENTS,
        )
        val target = input.ref?.let { "ref $it" } ?: DevtoolsLimits.clip(input.selector, 120)
        return runJson(js) { BrowserActionResult(text = DevtoolsFormat.inspect(it, target)) }
    }

    private suspend fun selectOption(input: BrowserActionInput): BrowserActionResult {
        if (input.ref == null && input.selector == null) {
            return BrowserActionResult.error("select_option requires 'selector' or 'ref'")
        }
        val option = input.text ?: return BrowserActionResult.error("select_option requires 'text' (the option's value or label)")
        return runJson(DevtoolsJS.selectOption(input.selector, input.ref, option)) { json ->
            BrowserActionResult(
                text = "Selected \"${json.optString("label")}\" (value=${json.optString("optionValue")}, index ${json.optInt("index")})",
            )
        }
    }

    private suspend fun storage(input: BrowserActionInput): BrowserActionResult {
        // The tab's current page, read from the WebView itself - there is no
        // URL argument, so another site's cookies or storage cannot be asked for.
        val pageUrl = withContext(Dispatchers.Main) { webView.url }
        if (pageUrl.isNullOrEmpty() || pageUrl == "about:blank") {
            return BrowserActionResult.error("storage: no page is loaded (navigate first)")
        }
        val keywords = input.keywords
            ?: input.filter?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
        return runJson(DevtoolsJS.storage()) { json ->
            // The document may have navigated between the two reads; only pair
            // the native cookies with the page when both are the same origin.
            val scriptUrl = json.optString("url", "")
            val sameOrigin = originOf(scriptUrl) != null && originOf(scriptUrl) == originOf(pageUrl)
            val nativeCookies = if (sameOrigin) {
                runCatching { CookieManager.getInstance().getCookie(scriptUrl) }.getOrNull()
            } else null
            BrowserActionResult(
                text = DevtoolsFormat.storage(json, nativeCookies, scriptUrl.ifEmpty { pageUrl }, keywords),
            )
        }
    }

    private fun originOf(url: String): String? = try {
        val u = android.net.Uri.parse(url)
        val scheme = u.scheme
        val host = u.host
        if (scheme.isNullOrEmpty() || host.isNullOrEmpty()) null else "$scheme://$host:${u.port}"
    } catch (_: Exception) {
        null
    }

    private suspend fun performance(): BrowserActionResult =
        runJson(DevtoolsJS.performance()) { BrowserActionResult(text = DevtoolsFormat.performance(it)) }

    private suspend fun console(input: BrowserActionInput): BrowserActionResult {
        flushHook()
        val read = consoleLog.read(
            since = input.since ?: 0,
            minLevel = input.level,
            filter = input.filter,
            limit = input.limit ?: DevtoolsLimits.CONSOLE_DEFAULT_SHOWN,
        )
        if (input.clear) consoleLog.clear()
        return BrowserActionResult(text = ConsoleFormatter.format(read, cleared = input.clear))
    }

    private suspend fun network(input: BrowserActionInput): BrowserActionResult {
        val justEnabled = !deepCapture
        if (justEnabled) {
            deepCapture = true
            withContext(Dispatchers.Main) { registerDocumentStartScript() }
        }
        flushHook() // also installs the fetch/XHR wrapper in the current page
        val id = input.requestId
        if (id != null) {
            val entry = networkLog.find(id)
                ?: return BrowserActionResult.error(
                    "network: no request #$id in the buffer (it holds the last ${DevtoolsLimits.NETWORK_RING}; list them with action network).",
                )
            return BrowserActionResult(text = NetworkFormatter.formatDetail(entry))
        }
        val read = networkLog.read(
            since = input.since ?: 0,
            filter = input.filter,
            problemsOnly = input.failed,
            limit = input.limit ?: DevtoolsLimits.NETWORK_DEFAULT_SHOWN,
        )
        if (input.clear) networkLog.clear()
        return BrowserActionResult(
            text = NetworkFormatter.formatList(read, deepCapture = true, deepJustEnabled = justEnabled, cleared = input.clear),
        )
    }

    private suspend fun evaluate(input: BrowserActionInput): BrowserActionResult {
        val script = input.script
        if (script.isNullOrBlank()) return BrowserActionResult.error("evaluate requires 'script'")
        val timeoutMs = DevtoolsLimits.timeoutMs(
            input.timeoutMs, DevtoolsLimits.EVAL_DEFAULT_TIMEOUT_MS, DevtoolsLimits.EVAL_MAX_TIMEOUT_MS, min = 1_000L,
        )
        // Expression form first (`1 + 1`, `document.title`), statement form
        // when that does not parse (`const x = …; return x`). A script that
        // does not parse never runs, so nothing is executed twice.
        val attempts: List<Pair<String, Boolean>> = listOf(
            DevtoolsJS.expressionCandidate(script) to true,
            script to false,
        )
        for ((body, asExpression) in attempts) {
            val token = newToken()
            val deferred = CompletableDeferred<String>()
            pendingResults[token] = deferred
            try {
                // The wrapper evaluates to a Promise ("{}"); null means it did not parse.
                val started = evalJs(DevtoolsJS.evaluate(body, token, asExpression))
                if (started == null) continue
                val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
                    ?: return BrowserActionResult.error(
                        "evaluate timed out after ${timeoutMs / 1000}s (the script's promise never settled). Pass a larger 'timeout' (max ${DevtoolsLimits.EVAL_MAX_TIMEOUT_MS / 1000}s) or avoid waiting inside the script - use the wait action.",
                    )
                val outcome = DevtoolsFormat.evaluate(raw, ranAsExpression = asExpression)
                return BrowserActionResult(text = outcome.text, success = outcome.success)
            } finally {
                pendingResults.remove(token)
            }
        }
        val detail = try {
            evalJs(com.openminis.app.browser.BrowserUseJS.syntaxCheck(script))
        } catch (e: com.openminis.app.browser.WebViewWedgedException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return BrowserActionResult.error(BrowserJSResult.syntaxErrorMessage(detail))
    }

    private suspend fun waitFor(input: BrowserActionInput): BrowserActionResult {
        val state = WaitSpec.normalizeState(input.state)
            ?: return BrowserActionResult.error("wait: 'state' must be one of ${WaitSpec.STATES.joinToString(", ")}")
        val spec = WaitSpec(
            selector = if (input.ref == null) input.selector else null,
            ref = input.ref,
            state = state,
            text = input.text,
            urlContains = input.urlContains,
            networkIdle = input.networkIdle,
        )
        if (spec.isEmpty) {
            return BrowserActionResult.error(
                "wait needs a condition: 'selector' or 'ref' (with optional 'state'), 'text', 'url_contains' and/or network_idle=true. To wait for the DOM to settle use wait_for_dom_stable.",
            )
        }
        val budget = DevtoolsLimits.timeoutMs(
            input.timeoutMs, DevtoolsLimits.WAIT_DEFAULT_TIMEOUT_MS, DevtoolsLimits.WAIT_MAX_TIMEOUT_MS,
        )
        val probeJs = (if (spec.networkIdle || spec.ref != null) DevtoolsJS.hook(deepCapture) + "\n" else "") +
            DevtoolsJS.waitProbe(spec.selector, spec.ref, spec.text)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + budget
        var lastPending = "page did not answer"
        while (true) {
            val decoded = evalJs(probeJs)
            val probe = try { if (decoded != null) JSONObject(decoded) else null } catch (_: Exception) { null }
            if (probe != null) {
                val last = lastNativeNetActivity.get()
                val nativeQuiet = if (last > 0L) System.currentTimeMillis() - last else -1L
                val check = spec.check(probe, nativeQuiet)
                if (check.fatal != null) return BrowserActionResult.error("wait: ${check.fatal}")
                if (check.satisfied) {
                    return BrowserActionResult(
                        text = "Condition met after ${System.currentTimeMillis() - startedAt}ms: ${spec.describe()}",
                    )
                }
                lastPending = check.pending
            }
            if (System.currentTimeMillis() + DevtoolsLimits.WAIT_POLL_MS > deadline) break
            delay(DevtoolsLimits.WAIT_POLL_MS)
        }
        return BrowserActionResult(
            text = "Timed out after ${budget}ms waiting for: ${spec.describe()}. Still pending: $lastPending",
            success = false,
        )
    }

    private fun newToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(24)
        repeat(24) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }

    companion object {
        private const val TAG = "BrowserDevtools"
        /** Upper bound on one evaluate result crossing the bridge (the script already limits itself to ~60k). */
        private const val MAX_RESULT_CHARS = 200_000
    }
}
