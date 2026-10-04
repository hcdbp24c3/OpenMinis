import Foundation

/// [T-ios-web-fetch] URL and address guards for public fetches.
///
/// Ported from tall-1997/OpenMinis-Linux (GPL-3, same licence family as this
/// repo), keeping the Android side (`tools/FetchUrlGuard.kt`) in step.
///
/// This matters because the caller is a language model reading untrusted content:
/// a page can tell it to fetch a cloud-metadata endpoint, and without this the
/// tool would hand over instance credentials.
///
/// ## Difference from Android, deliberately documented
///
/// Android installs a guarded `Dns` on the OkHttp client, so every address the
/// socket actually used is checked — including each redirect hop. URLSession
/// offers no equivalent hook, so here the guard is:
///
///  1. URL-level checks (scheme, literal host, dotted-quad / IPv6 literals);
///  2. an explicit resolution of the host with `getaddrinfo`, rejecting any
///     non-public answer, before the request is made;
///  3. the same URL-level check on the FINAL url after redirects.
///
/// That leaves a small TOCTOU window (a name that resolves publicly for step 2
/// and privately for the connection itself). It is accepted rather than papered
/// over: closing it needs a custom protocol client, and the practical exposure is
/// a cold DNS cache plus an attacker-controlled authoritative server, which is a
/// far higher bar than "the model was talked into fetching 169.254.169.254".
enum FetchUrlGuard {

    /// Non-null = refuse before any request is made.
    static func blockedReason(_ url: String) -> String? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "url is required" }
        guard let components = URLComponents(string: trimmed) else { return "invalid URL" }
        guard let scheme = components.scheme?.lowercased(), !scheme.isEmpty else {
            return "URL must include a scheme"
        }
        guard scheme == "http" || scheme == "https" else { return "only http/https URLs are allowed" }
        guard let host = components.host?.lowercased(), !host.isEmpty else { return "URL is missing a host" }
        if components.user != nil || components.password != nil { return "userinfo and fragments are not allowed" }
        if components.fragment != nil { return "userinfo and fragments are not allowed" }
        let bare = host.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        if bare.isEmpty || bare == "localhost" || bare.hasSuffix(".localhost") ||
            bare == "0.0.0.0" || bare == "::1" ||
            bare == "metadata.google.internal" || bare.hasSuffix(".internal") || bare.hasSuffix(".local") {
            return "blocked host: \(host)"
        }
        if isPrivateOrLoopbackIP(bare) { return "blocked private/loopback address: \(host)" }
        return nil
    }

    /// Resolve [host] and refuse when any answer is not globally routable.
    /// Returns nil when the host is acceptable (or unresolvable — the request
    /// itself will fail with a clearer error than this guard could give).
    static func blockedReasonForResolution(_ host: String) -> String? {
        guard !isPrivateOrLoopbackIP(host) else { return "blocked private/loopback address: \(host)" }
        var hints = addrinfo(
            ai_flags: 0,
            ai_family: AF_UNSPEC,
            ai_socktype: SOCK_STREAM,
            ai_protocol: 0,
            ai_addrlen: 0,
            ai_canonname: nil,
            ai_addr: nil,
            ai_next: nil
        )
        var info: UnsafeMutablePointer<addrinfo>?
        let status = getaddrinfo(host, nil, &hints, &info)
        guard status == 0, let first = info else { return nil }
        defer { freeaddrinfo(first) }

        var node: UnsafeMutablePointer<addrinfo>? = first
        while let current = node {
            if let sockaddr = current.pointee.ai_addr {
                if let address = stringAddress(sockaddr) {
                    if !isPublicAddress(address) {
                        return "blocked non-public DNS answer for \(host): \(address)"
                    }
                }
            }
            node = current.pointee.ai_next
        }
        return nil
    }

    /// Dotted-quad and IPv6 literal check (no DNS involved).
    static func isPrivateOrLoopbackIP(_ host: String) -> Bool {
        var h = host
        if h.hasPrefix("[") && h.hasSuffix("]") { h = String(h.dropFirst().dropLast()) }
        let lowered = h.lowercased()
        if lowered == "::1" || lowered.hasPrefix("fe80:") || lowered.hasPrefix("fc") || lowered.hasPrefix("fd") {
            return lowered.contains(":")
        }
        let parts = lowered.split(separator: ".")
        guard parts.count == 4 else { return false }
        var numbers: [Int] = []
        for part in parts {
            guard let value = Int(part), (0...255).contains(value) else { return false }
            numbers.append(value)
        }
        let a = numbers[0]
        let b = numbers[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254) || a == 0
    }

    /// True only for globally routable unicast addresses.
    static func isPublicAddress(_ address: String) -> Bool {
        let lowered = address.lowercased()
        if lowered.contains(":") {
            // IPv6: accept global unicast (2000::/3), reject everything else.
            let first = lowered.split(separator: ":").first.map(String.init) ?? ""
            guard let head = UInt16(first.isEmpty ? "0" : first, radix: 16) else { return false }
            let isGlobalUnicast = (head & 0xe000) == 0x2000
            let isUniqueLocal = (head & 0xfe00) == 0xfc00
            let isLinkLocal = (head & 0xffc0) == 0xfe80
            let isDocumentation = lowered.hasPrefix("2001:db8")
            return isGlobalUnicast && !isUniqueLocal && !isLinkLocal && !isDocumentation
        }
        let parts = lowered.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }
        let a = parts[0]
        let b = parts[1]
        let c = parts[2]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b >= 64 && b <= 127) ||
            (a == 168 && b == 63 && c == 129) ||
            (a == 169 && b == 254) ||
            (a == 172 && b >= 16 && b <= 31) ||
            (a == 192 && b == 168) ||
            (a == 192 && b == 0 && c == 0) ||
            (a == 192 && b == 0 && c == 2) ||
            (a == 198 && (b == 18 || b == 19)) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113))
    }

    private static func stringAddress(_ sockaddr: UnsafeMutablePointer<sockaddr>) -> String? {
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let length = socklen_t(sockaddr.pointee.sa_len)
        let result = getnameinfo(
            sockaddr, length,
            &host, socklen_t(host.count),
            nil, 0,
            NI_NUMERICHOST
        )
        guard result == 0 else { return nil }
        return String(cString: host)
    }
}
