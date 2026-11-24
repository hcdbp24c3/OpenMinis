package com.openminis.app.tools

import java.net.URI

/**
 * [T-web-m5] One canonical spelling per page, so two search engines (or two
 * rows of the same engine) that point at the same document are recognised as
 * one result, and so a cache entry is found again whichever spelling asked.
 *
 * The canonical form is a COMPARISON KEY, not something to fetch: the result
 * the model sees keeps its original URL except for tracking parameters (see
 * [stripTracking]), because rewriting a host or dropping a trailing slash can
 * change what a server answers.
 *
 * What is normalised in [key]:
 *  - scheme and host lower-cased, `http` and `https` treated as the same page;
 *  - a leading `www.` / `m.` label dropped, default ports dropped;
 *  - the fragment dropped (it never reaches the server);
 *  - tracking parameters dropped ([TRACKING_EXACT], [TRACKING_PREFIXES]);
 *  - remaining query parameters kept in their original order (order can be
 *    significant), an empty query dropped;
 *  - a trailing slash and a trailing `index.html`-style leaf dropped.
 *
 * Pure: no network, no Android.
 */
object WebUrlCanonicalizer {

    /** Parameters that only identify the click, never the document. */
    private val TRACKING_EXACT: Set<String> = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid",
        "igshid", "mc_cid", "mc_eid", "mkt_tok", "_hsenc", "_hsmi", "oly_anon_id",
        "oly_enc_id", "vero_id", "vero_conv", "ref_src", "ref_url", "spm", "scm",
        "share_source", "share_medium", "srsltid", "si", "ved", "ei", "sca_esv",
        "trk", "trkinfo", "cmpid", "ncid", "s_kwcid", "twclid", "ttclid", "rut",
    )

    private val TRACKING_PREFIXES: List<String> = listOf("utm_", "pk_", "hsa_", "mtm_", "stm_")

    private val INDEX_LEAVES: Set<String> = setOf(
        "index.html", "index.htm", "index.php", "index.asp", "index.aspx", "default.aspx",
    )

    fun isTrackingParam(name: String): Boolean {
        val n = name.lowercase()
        if (n in TRACKING_EXACT) return true
        for (prefix in TRACKING_PREFIXES) if (n.startsWith(prefix)) return true
        return false
    }

    /**
     * The URL with tracking parameters (and the fragment) removed and nothing
     * else changed. Returns the input unchanged when it does not parse as an
     * absolute http(s) URL — a search result with an odd URL is still a result.
     */
    fun stripTracking(url: String): String {
        val raw = url.trim()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return raw
        val scheme = raw.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return raw
        val hashAt = raw.indexOf('#')
        val noFragment = if (hashAt >= 0) raw.substring(0, hashAt) else raw
        val queryAt = noFragment.indexOf('?')
        if (queryAt < 0) return noFragment
        val base = noFragment.substring(0, queryAt)
        val kept = filterQuery(noFragment.substring(queryAt + 1))
        return if (kept.isEmpty()) base else "$base?$kept"
    }

    private fun filterQuery(query: String): String {
        if (query.isEmpty()) return ""
        val kept = ArrayList<String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val name = if (eq >= 0) part.substring(0, eq) else part
            if (isTrackingParam(name)) continue
            kept += part
        }
        return kept.joinToString("&")
    }

    /**
     * Comparison key for dedup and caching. Never empty for a non-blank input:
     * an unparsable URL falls back to its trimmed, lower-cased text.
     */
    fun key(url: String): String {
        val cleaned = stripTracking(url)
        val uri = try {
            URI(cleaned)
        } catch (_: Exception) {
            return cleaned.lowercase()
        }
        val rawHost = uri.host ?: return cleaned.lowercase()
        val host = bareHost(rawHost)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val port = uri.port
        val portPart = if (port < 0 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
            ""
        } else {
            ":$port"
        }
        var path = uri.rawPath.orEmpty()
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash >= 0 && path.substring(lastSlash + 1).lowercase() in INDEX_LEAVES) {
            path = path.substring(0, lastSlash + 1)
        }
        while (path.endsWith("/")) path = path.dropLast(1)
        val query = uri.rawQuery.orEmpty()
        val queryPart = if (query.isEmpty()) "" else "?$query"
        return "$host$portPart$path$queryPart"
    }

    /** Lower-cased host of [url] without `www.`/`m.`; empty when there is none. */
    fun host(url: String): String {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return hostByHand(url)
        }
        val h = uri.host ?: return hostByHand(url)
        return bareHost(h)
    }

    /** `URI` rejects some real-world URLs (spaces, `|`); the host is still readable. */
    private fun hostByHand(url: String): String {
        val raw = url.trim()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return ""
        var rest = raw.substring(schemeEnd + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) rest = rest.substring(0, end)
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        if (!rest.startsWith("[")) {
            val colon = rest.lastIndexOf(':')
            if (colon >= 0) rest = rest.substring(0, colon)
        }
        return bareHost(rest)
    }

    private fun bareHost(rawHost: String): String {
        var host = rawHost.lowercase().trim('.')
        if (host.startsWith("www.") && host.count { it == '.' } >= 2) host = host.substring(4)
        if (host.startsWith("m.") && host.count { it == '.' } >= 2) host = host.substring(2)
        return host
    }

    /**
     * A domain pattern as the model writes it — `example.com`, `www.Example.com`,
     * `https://example.com/docs`, `*.example.com`, `.example.com` — reduced to a
     * bare lower-case host. Empty when nothing usable is left.
     */
    fun normalizeDomain(raw: String): String {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return ""
        if (s.startsWith("site:")) s = s.substring(5)
        if (s.startsWith("-")) s = s.substring(1)
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) s = s.substring(0, end)
        if (s.startsWith("*.")) s = s.substring(2)
        s = s.trim('.')
        val colon = s.lastIndexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        if (s.startsWith("www.") && s.count { it == '.' } >= 2) s = s.substring(4)
        if (s.isEmpty() || s.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return ""
        return s
    }

    /** True when [host] is [domain] itself or one of its subdomains. */
    fun hostMatches(host: String, domain: String): Boolean {
        if (host.isEmpty() || domain.isEmpty()) return false
        return host == domain || host.endsWith(".$domain")
    }
}
