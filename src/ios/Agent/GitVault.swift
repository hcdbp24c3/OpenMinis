import Foundation
import Security

/// [T-git-vault] One credential store for every git host: HTTPS tokens AND SSH keys,
/// matched to the host the user actually names. Mirrors Android
/// `data/gitvault/GitVault.kt` — same rules, same storage split, so a user who moves
/// between the two sees the same behaviour.
///
/// Why a vault instead of a field per host: `RepoDigestTool` used to keep one token
/// slot per forge family (GitHub / GitLab / Gitea / Bitbucket), which already broke
/// down — a company GitLab and gitlab.com want DIFFERENT tokens, a second GitHub
/// Enterprise host is a second token, and SSH keys had nowhere to go at all. Entries
/// here are keyed by HOST PATTERN instead, so "git.corp.example" and "gitlab.com"
/// resolve independently and a wildcard ("*.corp.example") covers a fleet of
/// self-hosted forges without an entry each.
///
/// Two consumers, one store:
///  - ``RepoDigestTool`` asks for the HTTPS token of the host it is about to call (the
///    header shape stays provider-driven: Bearer / `PRIVATE-TOKEN` / `token …`).
///  - ``GitVaultMaterializer`` projects the vault into the iSH rootfs
///    (`~/.git-credentials`, `~/.ssh/config`, `~/.ssh/id_*`) so plain `git clone` /
///    `git push` in the terminal authenticate without the user exporting anything.
///
/// Storage split: hosts, labels and kinds are not secrets, so they live in
/// `UserDefaults` (cheap to list, and a Keychain reset can never hide the user's host
/// list). Secrets — token text, key material, key passphrase — live in the Keychain
/// keyed by entry id, so an entry can be edited (host, note) without rewriting its
/// secret, and a deleted entry drops its secret.
enum GitVaultKind: String, Codable, CaseIterable {
    /// HTTPS credential: a personal access token / app password.
    case token

    /// SSH private key (OpenSSH or PEM), optionally passphrase-protected.
    case sshKey
}

struct GitVaultEntry: Codable, Identifiable, Equatable {
    let id: String
    /// Host pattern: `github.com` (exact), `*.corp.example` (any subdomain), or `*`
    /// (catch-all, lowest priority). Never contains a scheme, port or path — paste
    /// whatever you like into the editor and ``GitVault/normalizePattern(_:)`` strips it.
    ///
    /// A catch-all is a deliberate, dangerous choice and the editor says so: matching
    /// `*` means the secret is sent to ANY host a tool call names, so a model talked
    /// into digesting an attacker's repository would hand it over. Exact hosts and
    /// wildcards are the safe forms; migration never creates a `*` entry.
    var host: String
    var kind: GitVaultKind
    /// HTTPS username. Usually left empty and defaulted per host at write time
    /// (`git` for most forges, `oauth2` for GitLab, `x-token-auth` for Bitbucket app
    /// passwords); SSH blocks always use `git`.
    var username: String = ""
    /// Free-form label shown in the list ("work GitLab", "deploy key").
    var note: String = ""
    var enabled: Bool = true

    static func new(host: String, kind: GitVaultKind) -> GitVaultEntry {
        GitVaultEntry(id: UUID().uuidString, host: host, kind: kind)
    }
}

enum GitVault {
    /// Plain (non-secret) metadata.
    private static let entriesKey = "git_vault_entries"
    /// One-shot marker so a deleted imported entry is not resurrected.
    private static let legacyImportedKey = "git_vault_legacy_imported"
    private static let keychainService = "com.openminis.app.gitvault"

    // MARK: - metadata

    static func entries() -> [GitVaultEntry] {
        guard let data = UserDefaults.standard.data(forKey: entriesKey) else { return [] }
        return (try? JSONDecoder().decode([GitVaultEntry].self, from: data)) ?? []
    }

    private static func write(_ list: [GitVaultEntry]) {
        guard let data = try? JSONEncoder().encode(list) else { return }
        UserDefaults.standard.set(data, forKey: entriesKey)
    }

    /// Insert or update by `id`. Order is preserved; new entries append.
    @discardableResult
    static func save(_ entry: GitVaultEntry) -> [GitVaultEntry] {
        var normalized = entry
        normalized.host = normalizePattern(entry.host)
        var current = entries()
        if let index = current.firstIndex(where: { $0.id == normalized.id }) {
            current[index] = normalized
        } else {
            current.append(normalized)
        }
        write(current)
        return current
    }

    @discardableResult
    static func delete(id: String) -> [GitVaultEntry] {
        let next = entries().filter { $0.id != id }
        write(next)
        deleteSecret(id: id)
        return next
    }

    @discardableResult
    static func setEnabled(id: String, enabled: Bool) -> [GitVaultEntry] {
        let next = entries().map { entry in
            var copy = entry
            if copy.id == id { copy.enabled = enabled }
            return copy
        }
        write(next)
        return next
    }

    // MARK: - secrets (Keychain)

    static func setSecret(id: String, value: String) {
        writeKeychain(value, account: secretAccount(id))
    }

    static func setPassphrase(id: String, value: String) {
        let account = passphraseAccount(id)
        if value.isEmpty {
            deleteKeychain(account: account)
        } else {
            writeKeychain(value, account: account)
        }
    }

    /// Token text or private-key material. Empty when unset.
    static func secret(id: String) -> String {
        readKeychain(account: secretAccount(id)) ?? ""
    }

    /// SSH key passphrase, or empty for an unencrypted key.
    static func passphrase(id: String) -> String {
        readKeychain(account: passphraseAccount(id)) ?? ""
    }

    static func hasSecret(id: String) -> Bool { !secret(id: id).isEmpty }

    private static func secretAccount(_ id: String) -> String { "secret_\(id)" }
    private static func passphraseAccount(_ id: String) -> String { "pass_\(id)" }

    private static func deleteSecret(id: String) {
        deleteKeychain(account: secretAccount(id))
        deleteKeychain(account: passphraseAccount(id))
    }

    private static func writeKeychain(_ value: String, account: String) {
        deleteKeychain(account: account)
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: data,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    private static func readKeychain(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8) else { return nil }
        return value
    }

    private static func deleteKeychain(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - matching

    /// Priority of a pattern against a host: higher wins. Nil when it does not match.
    ///
    /// Exact beats wildcard beats `*`, and a longer pattern beats a shorter one, so
    /// `git.corp.example` wins over `*.corp.example`, which wins over `*`. The host is
    /// compared WITHOUT port/userinfo — a vault entry says `git.corp.example`, not
    /// `git.corp.example:2222`, because the same host is reached on 22 for SSH and 443
    /// for HTTPS.
    static func patternScore(pattern: String, host: String) -> Int? {
        let p = normalizePattern(pattern)
        let h = normalizeHost(host)
        if p.isEmpty || h.isEmpty { return nil }
        if p == "*" { return 1_000 }
        if p == h { return 10_000 + p.count }
        if p.hasPrefix("*."), h.hasSuffix(String(p.dropFirst())), h.count > p.count - 1 {
            return 5_000 + p.count
        }
        // Bare-domain shorthand: an entry written as "corp.example" also covers its
        // subdomains, because a self-hosted forge usually answers on git.<domain> while
        // the token is issued for the domain as a whole. Never matches "evilcorp.example".
        let head = p.components(separatedBy: ".").dropLast().joined(separator: ".")
        if !head.contains("."), h == p || h.hasSuffix(".\(p)") {
            return 3_000 + p.count
        }
        return nil
    }

    /// The entry that should authenticate `host`, or nil. [kind] breaks a tie between
    /// an HTTPS token and an SSH key configured for the same host: a caller that will
    /// speak HTTPS asks for `.token` and gets the token entry rather than a key it
    /// cannot use. Disabled entries never match.
    static func match(host: String, kind: GitVaultKind, in list: [GitVaultEntry]? = nil) -> GitVaultEntry? {
        (list ?? entries())
            .filter { $0.enabled }
            .compactMap { entry -> (GitVaultEntry, Int)? in
                guard let score = patternScore(pattern: entry.host, host: host) else { return nil }
                // Same host, right kind first; a wrong-kind entry still ranks, so a
                // misconfigured vault degrades instead of going silent.
                return (entry, score + (entry.kind == kind ? 500 : 0))
            }
            .max(by: { $0.1 < $1.1 })?
            .0
    }

    /// The HTTPS token to send to `host`, or "" when the vault has none for it.
    static func token(host: String) -> String {
        guard let entry = match(host: host, kind: .token), entry.kind == .token else { return "" }
        return secret(id: entry.id)
    }

    // MARK: - host normalisation

    /// Lowercase, no scheme/userinfo/port/path, no trailing dot.
    static func normalizePattern(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s.isEmpty { return "" }
        if let host = hostOf(s) { return host }
        s = s.components(separatedBy: "://").last ?? s
        s = s.components(separatedBy: "/").first ?? s
        s = s.components(separatedBy: "@").last ?? s
        s = s.components(separatedBy: ":").first ?? s
        while s.hasSuffix(".") { s.removeLast() }
        return s
    }

    /// Lowercase, no userinfo, no port, no trailing dot.
    static func normalizeHost(_ raw: String) -> String {
        if let host = hostOf(raw) { return host }
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        s = s.components(separatedBy: "://").last ?? s
        s = s.components(separatedBy: "/").first ?? s
        s = s.components(separatedBy: "@").last ?? s
        s = s.components(separatedBy: ":").first ?? s
        while s.hasSuffix(".") { s.removeLast() }
        return s
    }

    /// The host inside anything a user might paste: a URL, an `ssh://` URL, an
    /// scp-style `git@host:owner/repo`, a `host/path` fragment, or `host:port/repo`.
    /// Nil for a bare `owner/repo` (there is no host to match).
    static func hostOf(_ raw: String) -> String? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }

        // scheme://[user[:pass]@]host[:port][/path]
        if let range = s.range(of: "^[a-zA-Z][a-zA-Z0-9+.\\-]*://(?:[^/@]*@)?([^/:?#]+)", options: .regularExpression) {
            let match = String(s[range])
            let afterScheme = match.components(separatedBy: "://").last ?? ""
            let hostPart = afterScheme.components(separatedBy: "@").last ?? afterScheme
            let host = hostPart.components(separatedBy: ":").first ?? hostPart
            return trimHost(host)
        }
        // scp-style: user@host:path
        if let at = s.firstIndex(of: "@"), let colon = s[at...].firstIndex(of: ":"), colon > s.index(after: at) {
            let host = String(s[s.index(after: at)..<colon])
            return trimHost(host)
        }
        // host/path or host:port/path, but NOT owner/repo — a host must have a dot or
        // a port, otherwise "owner/repo" (the most common bare form) would be read as
        // the host "owner".
        var head = s
        for separator in ["/", "?", "#"] {
            head = head.components(separatedBy: separator).first ?? head
        }
        // Strip userinfo: "user@host/path" is a remote, not a host named
        // "user@host".
        head = head.components(separatedBy: "@").last ?? head
        guard head.contains("."), !head.hasPrefix("."), !head.hasSuffix(".") else { return nil }
        if let colon = head.lastIndex(of: ":") {
            let port = head[head.index(after: colon)...]
            if !port.isEmpty, port.allSatisfy({ $0.isNumber }) {
                head = String(head[..<colon])
            }
        }
        return trimHost(head)
    }

    private static func trimHost(_ raw: String) -> String {
        var host = raw.lowercased()
        while host.hasSuffix(".") { host.removeLast() }
        return host.isEmpty ? "" : host
    }

    // MARK: - legacy migration

    /// One-shot import of the per-forge token fields the repo-digest screen used to
    /// own (`repo_digest_github_token` and friends in UserDefaults). Runs once ever,
    /// and never from the tool, so a read tool never writes.
    ///
    /// Three of the four fields name a host unambiguously and become ordinary enabled
    /// entries. The Gitea one does NOT: it was a single field covering "Gitea /
    /// Forgejo / Codeberg" with no host at all, and guessing `codeberg.org` would send
    /// a self-hosted instance's token to a public forge (and vice versa) — a credential
    /// leak, not a convenience. So it is imported DISABLED under `codeberg.org` with a
    /// note telling the user to set the real host and enable it.
    static func importRepoDigestLegacyOnce(defaults: UserDefaults = .standard) {
        if defaults.bool(forKey: legacyImportedKey) { return }
        let legacy: [(String, String)] = [
            ("github.com", defaults.string(forKey: "repo_digest_github_token") ?? ""),
            ("gitlab.com", defaults.string(forKey: "repo_digest_gitlab_token") ?? ""),
            ("bitbucket.org", defaults.string(forKey: "repo_digest_bitbucket_token") ?? ""),
        ]
        var current = entries()
        for (host, raw) in legacy {
            let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            let h = normalizePattern(host)
            if value.isEmpty || h.isEmpty { continue }
            // A host the user already configured wins: their entry may carry a
            // different kind, note or username.
            if current.contains(where: { $0.host == h }) { continue }
            var entry = GitVaultEntry.new(host: h, kind: .token)
            entry.note = "imported"
            setSecret(id: entry.id, value: value)
            current.append(entry)
        }
        let gitea = (defaults.string(forKey: "repo_digest_gitea_token") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !gitea.isEmpty, !current.contains(where: { $0.host == "codeberg.org" }) {
            var entry = GitVaultEntry.new(host: "codeberg.org", kind: .token)
            entry.note = "imported; the old Gitea field had no host — set the real one and enable"
            entry.enabled = false
            setSecret(id: entry.id, value: gitea)
            current.append(entry)
        }
        write(current)
        defaults.set(true, forKey: legacyImportedKey)
    }

    // MARK: - display

    /// Masked form for the UI: first 2 + asterisks + last 2 for anything long enough
    /// to be a real secret, all asterisks below that. Same rule as
    /// ``EnvVarRedactor``, so a secret looks the same wherever the app shows it.
    static func masked(_ secret: String) -> String {
        if secret.isEmpty { return "" }
        if secret.count < 8 { return String(repeating: "*", count: secret.count) }
        return String(secret.prefix(2)) + String(repeating: "*", count: secret.count - 4) + String(secret.suffix(2))
    }
}
