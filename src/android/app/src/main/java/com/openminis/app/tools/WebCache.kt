package com.openminis.app.tools

/**
 * [T-web-m5] A small in-memory cache with a TTL, an entry cap and a size cap,
 * least-recently-used first out.
 *
 * Used for two things, both of which repeat within one agent run:
 *  - search results: the same query asked again minutes later costs quota (or
 *    a scrape that may be rate-limited) for the same answer;
 *  - converted pages: reading a long page window by window must not download
 *    and convert it again for every window.
 *
 * In memory on purpose — nothing here is worth surviving a process restart,
 * and a page body on disk would be one more place user browsing is stored.
 *
 * The clock is injected so expiry is testable without sleeping; [sizeOf]
 * reports an entry's weight (characters) for the byte bound. An entry heavier
 * than the whole budget is simply not stored.
 */
class WebCache<V : Any>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val ttlMs: Long,
    private val sizeOf: (V) -> Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry<V>(val value: V, val storedAt: Long, val weight: Long)

    data class Hit<V>(val value: V, val ageMs: Long)

    // accessOrder = true: iteration starts at the least recently used entry.
    private val map = LinkedHashMap<String, Entry<V>>(16, 0.75f, true)
    private var weight = 0L

    @Synchronized
    fun get(key: String): Hit<V>? {
        val e = map[key] ?: return null
        val age = clock() - e.storedAt
        if (ttlMs <= 0L || age >= ttlMs || age < 0L) {
            removeEntry(key)
            return null
        }
        return Hit(e.value, age)
    }

    @Synchronized
    fun put(key: String, value: V) {
        if (maxEntries <= 0 || ttlMs <= 0L) return
        val w = sizeOf(value).coerceAtLeast(0L)
        removeEntry(key)
        if (w > maxWeight) return
        map[key] = Entry(value, clock(), w)
        weight += w
        evict()
    }

    @Synchronized
    fun remove(key: String) {
        removeEntry(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
        weight = 0L
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun totalWeight(): Long = weight

    private fun removeEntry(key: String) {
        val old = map.remove(key) ?: return
        weight -= old.weight
    }

    private fun evict() {
        // Expired entries first: they are dead weight whatever their recency.
        val now = clock()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.storedAt >= ttlMs) {
                weight -= e.value.weight
                it.remove()
            }
        }
        while (map.size > maxEntries || weight > maxWeight) {
            val eldest = map.entries.iterator()
            if (!eldest.hasNext()) break
            val e = eldest.next()
            weight -= e.value.weight
            eldest.remove()
        }
    }
}

/**
 * [T-web-m5] Whether a failed HTTP exchange is worth one more try, and how
 * long to wait first. Bounded on every axis: a tool call that sleeps for a
 * server's `Retry-After: 3600` is a hung agent.
 */
object HttpRetryPolicy {
    const val MAX_RETRIES = 2
    private const val MAX_WAIT_MS = 5_000L

    /** Statuses that describe the moment, not the request. */
    fun isRetryableStatus(code: Int): Boolean =
        code == 429 || code == 502 || code == 503 || code == 504

    /** Only requests that are safe to repeat. */
    fun isRetryableMethod(method: String): Boolean {
        val m = method.uppercase()
        return m == "GET" || m == "HEAD"
    }

    /**
     * Wait before retry number [attempt] (1-based), or null to give up.
     * `Retry-After` in seconds is honoured up to [MAX_WAIT_MS]; a longer demand
     * means "not now", and the caller reports it instead of waiting.
     */
    fun delayMs(attempt: Int, retryAfter: String?): Long? {
        if (attempt < 1 || attempt > MAX_RETRIES) return null
        val asked = parseRetryAfterSeconds(retryAfter)
        if (asked != null) {
            val ms = asked * 1000L
            return if (ms > MAX_WAIT_MS) null else ms.coerceAtLeast(250L)
        }
        return if (attempt == 1) 600L else 1_800L
    }

    /** Delta-seconds only; an HTTP-date is treated as unknown (default backoff). */
    fun parseRetryAfterSeconds(raw: String?): Long? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || v.length > 9 || !v.all { it.isDigit() }) return null
        return v.toLongOrNull()
    }

    /** One line the model can act on. */
    fun describe(code: Int, retryAfter: String?): String = when (code) {
        429 -> {
            val s = parseRetryAfterSeconds(retryAfter)
            if (s != null) "rate limited (HTTP 429, retry after ${s}s)" else "rate limited (HTTP 429)"
        }
        401 -> "unauthorized (HTTP 401) — the key or credentials were rejected"
        403 -> "forbidden (HTTP 403) — the server refused this client or key"
        404 -> "not found (HTTP 404)"
        in 500..599 -> "server error (HTTP $code)"
        else -> "HTTP $code"
    }
}
