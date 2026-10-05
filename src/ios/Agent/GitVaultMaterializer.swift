import Foundation

/// [T-git-vault] Projects the vault into the iSH rootfs so plain `git clone`,
/// `git push`, `git fetch` work without the user exporting anything by hand. Mirrors
/// Android `data/gitvault/GitVaultMaterializer.kt`.
///
/// What gets written, and why each piece:
///  - `~/.git-credentials` — one `https://user:token@host` line per token entry, with
///    `credential.helper = store` in `~/.gitconfig`. `store` reads that file, so any
///    https remote resolves without an interactive prompt. SSH remotes never consult
///    it, so it is harmless on its own.
///  - `~/.ssh/config` — one `Host <pattern>` block per SSH key. This is what makes
///    `git@git.corp.example:team/app.git` pick the RIGHT key of several: the block
///    pins `IdentityFile` + `IdentitiesOnly yes`, which stops ssh from offering every
///    key until the server locks the account out (the classic "Too many
///    authentication failures").
///  - `~/.ssh/id_minis_<id>` — key material, 0600, in a 0700 directory. Named after the
///    entry id (NOT the user's host label) so two keys for the same host don't
///    overwrite each other, and a deleted entry's file is identifiable.
///
/// Everything is written between `# >>> minis-git-vault` / `# <<< minis-git-vault`
/// markers and ``mergeMarked(original:block:)`` replaces ONLY that block: a
/// `.gitconfig` the user already tuned, or an `.ssh/config` with their own hosts,
/// survives a re-sync. The generators are pure string functions on purpose — the
/// tests exercise them without a rootfs, and the writer is a thin shell around them.
///
/// A stored passphrase is deliberately NOT projected into the sandbox: ssh has no way
/// to consume one without either an agent or an askpass helper, and both would mean
/// writing the passphrase (or a script containing it) to a file that outlives the
/// command. A passphrase-protected key therefore prompts once in the terminal, and
/// `ssh-add`/an agent is the supported way to avoid that.
struct GitVaultSyncResult: Equatable {
    let home: String
    let tokens: Int
    let keys: Int
    let skipped: Int
    /// Enabled TOKEN entries whose host is a wildcard. They authenticate the API tools
    /// (which match the host themselves) but cannot be expressed in
    /// `~/.git-credentials`, because git's `store` helper matches literal hosts — so
    /// shell `git` needs an exact-host entry. Counted so the UI can say so instead of
    /// silently leaving a clone unauthenticated.
    let wildcards: Int
}

enum GitVaultMaterializer {
    static let markerStart = "# >>> minis-git-vault"
    static let markerEnd = "# <<< minis-git-vault"

    /// Guest home: the iSH rootfs data dir is `/`, so `data/root` is `~`.
    private static let guestHome = "/root"

    /// Key filenames are derived from the entry id; this is the shared prefix.
    private static let keyPrefix = "id_minis_"

    // MARK: - pure generators

    /// SSH key file name for an entry, relative to `~/.ssh`.
    static func keyFileName(_ entry: GitVaultEntry) -> String {
        let sanitized = entry.id.map { ch -> Character in
            ch.isLetter || ch.isNumber || ch == "." || ch == "_" || ch == "-" ? ch : "_"
        }
        return keyPrefix + String(sanitized)
    }

    /// `~/.git-credentials` body. Empty when no token entries are enabled.
    ///
    /// The user name defaults per host because git sends it verbatim and forges
    /// disagree: GitLab wants `oauth2` for a project/personal access token, Bitbucket
    /// app passwords want `x-token-auth`, everyone else accepts `git` (GitHub ignores
    /// it entirely for a PAT).
    static func credentialsFile(_ pairs: [(GitVaultEntry, String)]) -> String {
        let lines = pairs
            .filter { $0.0.kind == .token && $0.0.enabled && !$0.1.isEmpty }
            // Wildcards are skipped: git matches `store` lines by literal host, so
            // `https://git:tok@*.corp.example` would never match anything.
            .filter { !$0.0.host.contains("*") }
            .map { "https://\(userFor($0.0)):\(encodeSecret($0.1))@\($0.0.host)" }
        return lines.isEmpty ? "" : lines.joined(separator: "\n") + "\n"
    }

    /// `~/.gitconfig` body: just the credential helper, inside markers, so it can be
    /// merged into a file that already has `[user]` and `[alias]` sections.
    static func gitConfigBlock() -> String {
        "[credential]\n\thelper = store\n"
    }

    /// `~/.ssh/config` block for every enabled SSH key.
    ///
    /// `HostName` is only written for a pattern without a wildcard: `Host *.corp.example`
    /// plus `HostName *.corp.example` would be nonsense (ssh would literally try to
    /// resolve the asterisk), while for `git.corp.example` it is redundant but harmless
    /// and lets a user point a short alias at the real host.
    static func sshConfigBlock(_ pairs: [(GitVaultEntry, String)], keyFiles: [String: String]) -> String {
        let blocks = pairs
            .filter { $0.0.kind == .sshKey && $0.0.enabled && !$0.1.isEmpty }
            .map { pair -> String in
                let entry = pair.0
                let file = keyFiles[entry.id] ?? keyFileName(entry)
                var lines = ["Host \(entry.host)"]
                if !entry.host.contains("*") { lines.append("  HostName \(entry.host)") }
                lines.append("  User \(entry.username.trimmingCharacters(in: .whitespaces).isEmpty ? "git" : entry.username.trimmingCharacters(in: .whitespaces))")
                lines.append("  IdentityFile ~/.ssh/\(file)")
                // Without IdentitiesOnly, ssh offers every key it can find — with
                // several vault keys for one host (personal + deploy) that trips the
                // server's auth limit before the right key is tried.
                lines.append("  IdentitiesOnly yes")
                return lines.joined(separator: "\n") + "\n"
            }
        return blocks.joined()
    }

    /// Replace the marked block in `original` with `block`, preserving everything the
    /// user wrote around it. An empty `block` removes ours outright. A file with no
    /// markers gets the block appended.
    static func mergeMarked(original: String, block: String) -> String {
        let startRange = original.range(of: markerStart)
        let endRange = original.range(of: markerEnd)
        var head = original
        var tail = ""
        if let startRange, let endRange, startRange.lowerBound < endRange.lowerBound {
            head = String(original[..<startRange.lowerBound])
            tail = String(original[endRange.upperBound...])
        }
        head = trimEnd(head, character: "\n")
        tail = trimStart(tail, character: "\n")
        let mine = block.isEmpty ? "" : "\(markerStart)\n\(trimEnd(block, character: "\n"))\n\(markerEnd)"
        var parts: [String] = []
        if !head.isEmpty { parts.append(head) }
        if !mine.isEmpty { parts.append(mine) }
        if !tail.isEmpty { parts.append(tail) }
        return parts.isEmpty ? "" : parts.joined(separator: "\n\n") + "\n"
    }

    /// Bitbucket app passwords are the one place a username must not be `git`.
    static func userFor(_ entry: GitVaultEntry) -> String {
        let explicit = entry.username.trimmingCharacters(in: .whitespaces)
        if !explicit.isEmpty { return explicit }
        if entry.host.hasSuffix("bitbucket.org") { return "x-token-auth" }
        if entry.host.hasSuffix("gitlab.com") { return "oauth2" }
        return "git"
    }

    /// Percent-encode the characters that would otherwise break the URL grammar in
    /// `https://user:token@host`. Tokens legitimately contain `+`, `/`, `=` (base64) and
    /// occasionally `@` or `:` (pasted app passwords), all of which are fine
    /// percent-encoded and fatal raw.
    static func encodeSecret(_ secret: String) -> String {
        var out = ""
        for scalar in secret.unicodeScalars {
            let ch = Character(scalar)
            let safe = ch.isLetter || ch.isNumber || ch == "-" || ch == "." || ch == "_" || ch == "~"
            if safe {
                out.append(ch)
            } else {
                out += String(format: "%%%02X", scalar.value & 0xFF)
            }
        }
        return out
    }

    // MARK: - writer

    /// Materialize the vault under the guest's home directory and return what was
    /// written. Reads every secret once, so a vault with 20 entries does one Keychain
    /// pass rather than 20. Entries without a secret are skipped (a user can save a
    /// host first and paste the token later) and counted in `.skipped`.
    @discardableResult
    static func sync(dataPath: URL) -> GitVaultSyncResult {
        let home = dataPath.appendingPathComponent(guestHome.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
        let all = GitVault.entries()
        if all.isEmpty {
            clear(home: home)
            return GitVaultSyncResult(home: guestHome, tokens: 0, keys: 0, skipped: 0, wildcards: 0)
        }

        let withSecrets: [(GitVaultEntry, String)] = all.map { ($0, GitVault.secret(id: $0.id)) }
        let skipped = withSecrets.filter { $0.0.enabled && $0.1.isEmpty }.count
        let tokens = withSecrets.filter { $0.0.kind == .token && $0.0.enabled && !$0.1.isEmpty }.count
        let keys = withSecrets.filter { $0.0.kind == .sshKey && $0.0.enabled && !$0.1.isEmpty }.count
        let wildcards = withSecrets.filter {
            $0.0.kind == .token && $0.0.enabled && !$0.1.isEmpty && $0.0.host.contains("*")
        }.count

        writeFile(home.appendingPathComponent(".git-credentials"), contents: credentialsFile(withSecrets), ownerOnly: true)

        let gitConfig = home.appendingPathComponent(".gitconfig")
        let gitBlock = (tokens - wildcards) > 0 ? gitConfigBlock() : ""
        writeFile(gitConfig, contents: mergeMarked(original: readOrEmpty(gitConfig), block: gitBlock), ownerOnly: false)

        let sshDir = home.appendingPathComponent(".ssh")
        if keys > 0 {
            try? FileManager.default.createDirectory(at: sshDir, withIntermediateDirectories: true)
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: sshDir.path)
        }

        var keyFiles: [String: String] = [:]
        for (entry, secret) in withSecrets {
            guard entry.kind == .sshKey, entry.enabled, !secret.isEmpty else { continue }
            let name = keyFileName(entry)
            keyFiles[entry.id] = name
            // OpenSSH demands a trailing newline; a key pasted from a file picker or a
            // web textarea often arrives without one and ssh refuses it outright.
            let body = secret.hasSuffix("\n") ? secret : secret + "\n"
            writeFile(sshDir.appendingPathComponent(name), contents: body, ownerOnly: true)
        }

        let sshConfig = sshDir.appendingPathComponent("config")
        writeFile(sshConfig, contents: mergeMarked(original: readOrEmpty(sshConfig), block: sshConfigBlock(withSecrets, keyFiles: keyFiles)), ownerOnly: true)

        return GitVaultSyncResult(home: guestHome, tokens: tokens, keys: keys, skipped: skipped, wildcards: wildcards)
    }

    /// Remove our blocks (and our key files) without touching anything else.
    private static func clear(home: URL) {
        let gitConfig = home.appendingPathComponent(".gitconfig")
        if FileManager.default.fileExists(atPath: gitConfig.path) {
            writeFile(gitConfig, contents: mergeMarked(original: readOrEmpty(gitConfig), block: ""), ownerOnly: false)
        }
        let sshDir = home.appendingPathComponent(".ssh")
        let sshConfig = sshDir.appendingPathComponent("config")
        if FileManager.default.fileExists(atPath: sshConfig.path) {
            writeFile(sshConfig, contents: mergeMarked(original: readOrEmpty(sshConfig), block: ""), ownerOnly: true)
        }
        if let items = try? FileManager.default.contentsOfDirectory(atPath: sshDir.path) {
            for item in items where item.hasPrefix(keyPrefix) {
                try? FileManager.default.removeItem(at: sshDir.appendingPathComponent(item))
            }
        }
        try? FileManager.default.removeItem(at: home.appendingPathComponent(".git-credentials"))
    }

    private static func readOrEmpty(_ url: URL) -> String {
        (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }

    /// Write `contents` with the perms git/ssh insist on: a world-readable private key
    /// or `.git-credentials` is exactly the leak this feature would otherwise create.
    private static func writeFile(_ url: URL, contents: String, ownerOnly: Bool) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? contents.write(to: url, atomically: true, encoding: .utf8)
        try? FileManager.default.setAttributes(
            [.posixPermissions: ownerOnly ? 0o600 : 0o644],
            ofItemAtPath: url.path
        )
    }

    private static func trimEnd(_ value: String, character: Character) -> String {
        var s = value
        while s.hasSuffix(String(character)) { s.removeLast() }
        return s
    }

    private static func trimStart(_ value: String, character: Character) -> String {
        var s = value
        while s.hasPrefix(String(character)) { s.removeFirst() }
        return s
    }
}
