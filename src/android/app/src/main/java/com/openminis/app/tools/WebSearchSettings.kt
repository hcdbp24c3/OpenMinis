package com.openminis.app.tools

import android.content.Context

/**
 * [T-android-web-search] User-configurable search backends for [WebSearchTool].
 *
 * The provider set mirrors Kelivo's (`lib/core/services/search/providers/`,
 * 26 services) so the two apps offer the same choices; the ones with no key or
 * URL of their own (DuckDuckGo, Bing's HTML endpoint) stay first because the
 * tool has to work on a fresh install.
 *
 * [Engine] is the storage identity: the `id` strings are the same as Kelivo's
 * service types and the preference keys keep the historical Android spelling, so
 * an existing install's engine choice keeps working after this expansion.
 *
 * ## Multiple keys (Kelivo's "multiple search")
 *
 * Every keyed backend stores a LIST of keys, not one: the detail screen accepts a
 * batch paste (newlines, commas, semicolons or spaces) and [SearchKeyRotator]
 * hands out the next key per request, round-robin, in memory. That is what
 * Kelivo's `SearchApiKeyRotator` does — the point is to keep a per-key quota from
 * being the ceiling on how much searching an agent can do.
 */
object WebSearchSettings {
    const val PREFS = "web_search_prefs"
    const val KEY_ENGINE = "engine"
    const val KEY_SEARXNG_URL = "searxng_url"
    const val KEY_SEARXNG_AUTH = "searxng_auth"
    const val KEY_FALLBACK = "fallback_ddg"
    const val KEY_CUSTOM_URL = "custom_url"
    const val KEY_CUSTOM_KEY = "custom_key"
    const val KEY_CUSTOM_KEY_HEADER = "custom_key_header"

    /**
     * Engines, in the order the settings screen lists them: the two no-key
     * backends first, then the keyed ones roughly by how common they are.
     *
     * [needsKey] false means the request carries no credential at all (the
     * no-key web endpoints); [defaultUrl] non-null means the endpoint is fixed;
     * [urlKey] non-null means the user may override it and the override is stored
     * under that preference key (Kelivo exposes the same URL fields).
     */
    enum class Engine(
        val id: String,
        val displayName: String,
        val needsKey: Boolean = false,
        val defaultUrl: String? = null,
        val urlKey: String? = null,
    ) {
        DDG("ddg", "DuckDuckGo HTML"),
        BING_LOCAL("bing_local", "Bing (HTML, no key)"),
        SEARXNG("searxng", "SearXNG"),
        CUSTOM("custom", "Custom"),
        TAVILY("tavily", "Tavily", needsKey = true, defaultUrl = "https://api.tavily.com/search"),
        BING("bing", "Bing Web Search API", needsKey = true, defaultUrl = "https://api.bing.microsoft.com/v7.0/search"),
        BRAVE("brave", "Brave Search", needsKey = true, defaultUrl = "https://api.search.brave.com/res/v1/web/search"),
        EXA("exa", "Exa", needsKey = true, defaultUrl = "https://api.exa.ai/search", urlKey = "exa_url"),
        JINA("jina", "Jina Search", needsKey = true, defaultUrl = "https://s.jina.ai/", urlKey = "jina_url"),
        BOCHA("bocha", "Bocha", needsKey = true, defaultUrl = "https://api.bochaai.com/v1/web-search"),
        ZHIPU("zhipu", "Zhipu Web Search", needsKey = true, defaultUrl = "https://open.bigmodel.cn/api/paas/v4/web_search"),
        KAGI("kagi", "Kagi", needsKey = true, defaultUrl = "https://kagi.com/api/v1/search"),
        LINKUP("linkup", "LinkUp", needsKey = true, defaultUrl = "https://api.linkup.so/v1/search"),
        METASO("metaso", "Metaso", needsKey = true, defaultUrl = "https://metaso.cn/api/v1/search"),
        OLLAMA("ollama", "Ollama Cloud", needsKey = true, defaultUrl = "https://ollama.com/api/web_search"),
        PARALLEL("parallel", "Parallel", needsKey = true, defaultUrl = "https://api.parallel.ai/v1/search"),
        PERPLEXITY("perplexity", "Perplexity", needsKey = true, defaultUrl = "https://api.perplexity.ai/search"),
        QUERIT("querit", "Querit", needsKey = true, defaultUrl = "https://api.querit.ai/v1/search"),
        SERPER("serper", "Serper (Google)", needsKey = true, defaultUrl = "https://google.serper.dev/search"),
        STEPFUN("stepfun", "StepFun", needsKey = true, defaultUrl = "https://api.stepfun.com/v1/search", urlKey = "stepfun_url"),
        TINYFISH("tinyfish", "TinyFish", needsKey = true, defaultUrl = "https://api.search.tinyfish.ai", urlKey = "tinyfish_url"),
        YOU("you", "You.com", needsKey = true, defaultUrl = "https://ydc-index.io/v1/search"),
        FIRECRAWL("firecrawl", "Firecrawl", needsKey = true, defaultUrl = "https://api.firecrawl.dev/v2/search", urlKey = "firecrawl_url"),
        ANYSEARCH("anysearch", "AnySearch", needsKey = true, defaultUrl = "https://api.anysearch.com/v1/search", urlKey = "anysearch_url"),
        DOUBAO("doubao", "Doubao", needsKey = true, defaultUrl = "https://open.feedcoopapi.com/search_api/web_search"),
        KIMI("kimi", "Kimi", needsKey = true, defaultUrl = "https://api.moonshot.cn/v1/tools/web_search"),
        GROK("grok", "Grok (xAI)", needsKey = true, defaultUrl = "https://api.x.ai/v1/responses", urlKey = "grok_url"),
        ;

        /** Preference key holding this engine's key list, e.g. `tavily_keys`. */
        val keysPref: String get() = "${id}_keys"

        /** Legacy single-key preference, kept readable for migration. */
        val legacyKeyPref: String get() = "${id}_key"

        /** Endpoint in use: the user's override when set, else the default. */
        fun resolvedUrl(context: Context?): String {
            val key = urlKey
            val override = if (key == null || context == null) "" else {
                prefs(context).getString(key, null).orEmpty()
            }
            if (override.isNotBlank()) return override.trim()
            return defaultUrl.orEmpty()
        }

        fun urlOverride(context: Context?): String {
            val key = urlKey ?: return ""
            if (context == null) return ""
            return prefs(context).getString(key, null).orEmpty()
        }

        companion object {
            fun fromId(raw: String?): Engine =
                entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: DDG
        }
    }

    fun engine(context: Context): Engine {
        val raw = prefs(context).getString(KEY_ENGINE, null)
        if (!raw.isNullOrBlank()) return Engine.fromId(raw)
        // No explicit choice: prefer a backend the user has already configured,
        // else DuckDuckGo (the one that needs nothing).
        return configuredKeyed(context).firstOrNull() ?: Engine.DDG
    }

    fun setEngine(context: Context, engine: Engine) {
        prefs(context).edit().putString(KEY_ENGINE, engine.id).apply()
    }

    /** Keyed backends the user has already filled in, in enum order. */
    fun configuredKeyed(context: Context): List<Engine> =
        Engine.entries.filter { it.needsKey && keys(context, it).isNotEmpty() }

    // ── keys ────────────────────────────────────────────────────────────────

    /**
     * This engine's keys, in rotation order.
     *
     * Reads the list first and falls back to the legacy single-key preference, so
     * an install that configured one key before multiple keys existed keeps that
     * key without any migration step.
     */
    fun keys(context: Context?, engine: Engine): List<String> {
        if (context == null) return emptyList()
        val stored = prefs(context).getString(engine.keysPref, null)
        val fromList = if (stored.isNullOrBlank()) emptyList() else parseKeyBatch(stored)
        if (fromList.isNotEmpty()) return fromList
        val legacy = prefs(context).getString(engine.legacyKeyPref, null)
        return if (legacy.isNullOrBlank()) emptyList() else parseKeyBatch(legacy)
    }

    fun setKeys(context: Context, engine: Engine, rawKeys: String) {
        val parsed = parseKeyBatch(rawKeys)
        prefs(context).edit().apply {
            putString(engine.keysPref, parsed.joinToString("\n"))
            // The legacy slot is cleared once a list exists, so the two cannot
            // disagree about what is configured.
            remove(engine.legacyKeyPref)
        }.apply()
    }

    fun setKeys(context: Context, engine: Engine, keys: List<String>) {
        setKeys(context, engine, keys.joinToString("\n"))
    }

    /** Single-key convenience used by the tool's error paths and the UI's summary. */
    fun apiKey(context: Context?, engine: Engine): String = keys(context, engine).firstOrNull().orEmpty()

    /**
     * Split a pasted batch into individual keys. Separators are newlines, commas,
     * semicolons or whitespace (Kelivo's `SearchApiKeyRotator.parseBatch`), so
     * pasting a column out of a spreadsheet works as-is.
     */
    fun parseKeyBatch(input: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (part in input.split(Regex("[\\s,;]+"))) {
            val key = part.trim()
            if (key.isNotEmpty()) seen.add(key)
        }
        return seen.toList()
    }

    /** `abcd••••wxyz` — enough to tell two keys apart without showing either. */
    fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.length <= 8) return "••••••••"
        return trimmed.take(4) + "••••" + trimmed.takeLast(4)
    }

    // ── SearXNG / custom ────────────────────────────────────────────────────

    fun searxngUrl(context: Context): String =
        prefs(context).getString(KEY_SEARXNG_URL, null).orEmpty()

    fun setSearxngUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SEARXNG_URL, url.trim()).apply()
    }

    /** `user:password` for instances behind Basic auth; blank = anonymous. */
    fun searxngAuth(context: Context): String =
        prefs(context).getString(KEY_SEARXNG_AUTH, null).orEmpty()

    fun setSearxngAuth(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SEARXNG_AUTH, value.trim()).apply()
    }

    fun customUrl(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_URL, null).orEmpty()

    fun setCustomUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_CUSTOM_URL, url.trim()).apply()
    }

    fun customKey(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_KEY, null).orEmpty()

    fun setCustomKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_CUSTOM_KEY, key.trim()).apply()
    }

    fun customKeyHeader(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_KEY_HEADER, null).orEmpty()

    fun setCustomKeyHeader(context: Context, header: String) {
        prefs(context).edit().putString(KEY_CUSTOM_KEY_HEADER, header.trim()).apply()
    }

    // ── misc ────────────────────────────────────────────────────────────────

    fun urlOverride(context: Context?, engine: Engine): String = engine.urlOverride(context)

    fun setUrlOverride(context: Context, engine: Engine, url: String) {
        val key = engine.urlKey ?: return
        prefs(context).edit().putString(key, url.trim()).apply()
    }

    fun fallbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FALLBACK, true)

    fun setFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FALLBACK, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * [T-android-web-search] Round-robin over an engine's key list.
 *
 * In memory on purpose, exactly like Kelivo's `SearchApiKeyRotator`: the cursor
 * is a "spread the load over this run" device, not user state worth persisting —
 * restarting the app and starting again from the first key costs nothing, whereas
 * a persisted cursor can park a bad key at the head of the rotation.
 *
 * A single-key pool never advances, so behaviour for the common case is exactly
 * what it was before multiple keys existed.
 */
object SearchKeyRotator {
    private val cursors = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Pure half: round-robin over [pool] for [engineId]. A single-key pool never
     * advances, so behaviour for the common case is exactly what it was before
     * multiple keys existed. Split out from the Context overload so it can be
     * tested without an Android runtime (this module's unit tests are plain JVM).
     */
    fun next(engineId: String, pool: List<String>): String {
        if (pool.isEmpty()) return ""
        if (pool.size == 1) return pool.first()
        val index = (cursors[engineId] ?: 0) % pool.size
        cursors[engineId] = (index + 1) % pool.size
        return pool[index]
    }

    fun select(context: Context?, engine: WebSearchSettings.Engine): String =
        next(engine.id, WebSearchSettings.keys(context, engine))

    /** Test seam: forget the rotation state. */
    fun reset() = cursors.clear()
}
