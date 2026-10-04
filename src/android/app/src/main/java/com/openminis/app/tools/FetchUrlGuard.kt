package com.openminis.app.tools

import okhttp3.Dns
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * [T-android-web-fetch] URL and DNS guards for public fetches.
 *
 * Ported from tall-1997/OpenMinis-Linux (GPL-3, same licence family as this
 * repo). Two layers, because either alone is insufficient:
 *
 *  - [blockedReason] inspects the URL the model asked for: scheme, literal host,
 *    and dotted-quad / IPv6 literals. It cannot see a name that RESOLVES to a
 *    private address.
 *  - [publicInternetDns] is installed on the OkHttp client, so every address the
 *    connection actually used is checked — and it is consulted again for each
 *    redirect hop, which is exactly how "public URL that 302s to 169.254.169.254"
 *    (cloud metadata) gets stopped.
 *
 * This matters because the caller is a language model reading untrusted content:
 * a page can tell it to fetch a cloud-metadata endpoint, and without this the
 * tool would happily hand over instance credentials.
 */
object FetchUrlGuard {

    /** Non-null = refuse before any socket is opened. */
    fun blockedReason(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "url is required"
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return "invalid URL"
        }
        val scheme = uri.scheme?.lowercase() ?: return "URL must include a scheme"
        if (scheme != "http" && scheme != "https") {
            return "only http/https URLs are allowed"
        }
        val host = uri.host?.lowercase()?.trim('.') ?: return "URL is missing a host"
        if (uri.rawUserInfo != null || uri.rawFragment != null) return "userinfo and fragments are not allowed"
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") ||
            host == "0.0.0.0" || host == "::1" || host == "[::1]" ||
            host == "metadata.google.internal" || host.endsWith(".internal") ||
            host.endsWith(".local")
        ) {
            return "blocked host: $host"
        }
        if (isPrivateOrLoopbackIp(host)) return "blocked private/loopback address: $host"
        return null
    }

    /** OkHttp DNS that refuses any non-public answer, redirects included. */
    internal fun publicInternetDns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = InetAddress.getAllByName(hostname).toList()
            if (resolved.isEmpty() || resolved.any(::isUnsafeAddress)) {
                throw UnknownHostException("Blocked non-public DNS answer for $hostname")
            }
            return resolved
        }
    }

    /** True for anything that is not a globally routable unicast address. */
    internal fun isUnsafeAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) ||          // CGNAT
                (a == 168 && b == 63 && c == 129) ||   // AWS metadata
                (a == 169 && b == 254) ||              // link-local + cloud metadata
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 198 && b in 18..19) ||           // benchmarking
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)
        }
        if (bytes.size == 16) {
            val first = bytes[0]
            val second = bytes[1]
            val globalUnicast = (first and 0xe0) == 0x20
            val uniqueLocal = (first and 0xfe) == 0xfc
            val documentation = first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8
            return !globalUnicast || uniqueLocal || documentation
        }
        return true
    }

    internal fun isPrivateOrLoopbackIp(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) {
            return h.contains(':')
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val a = nums[0]
        val b = nums[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 169 && b == 254) || a == 0
    }
}
