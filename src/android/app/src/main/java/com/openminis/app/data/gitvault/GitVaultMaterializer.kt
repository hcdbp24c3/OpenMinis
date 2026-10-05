package com.openminis.app.data.gitvault

import android.content.Context
import java.io.File

/**
 * [T-git-vault] Projects the vault into the alpine sandbox so plain `git clone`,
 * `git push`, `git fetch` work without the user exporting anything by hand.
 *
 * What gets written, and why each piece:
 *  - `~/.git-credentials` — one `https://user:token@host` line per token entry, with
 *    `credential.helper = store` in `~/.gitconfig`. `store` reads that file, so any
 *    https remote git is handed by the library resolves without an interactive
 *    prompt. SSH remotes are untouched by it, so it is harmless on its own.
 *  - `~/.ssh/config` — one `Host <pattern>` block per SSH key. This is what makes
 *    `git@git.corp.example:team/app.git` pick the RIGHT key of several: the block
 *    pins `IdentityFile` + `IdentitiesOnly yes`, which stops ssh from offering
 *    every key in the agent until the server locks the account out (the classic
 *    "Too many authentication failures").
 *  - `~/.ssh/id_minis_<id>` — key material, 0600, in a 0700 directory. Named after
 *    the entry id (NOT the user's host label) so two keys for the same host don't
 *    overwrite each other, and so a deleted entry's file is identifiable.
 *
 * Everything is written between `# >>> minis-git-vault` / `# <<< minis-git-vault`
 * markers and [mergeMarked] replaces ONLY that block: a `.gitconfig` the user
 * already tuned, or an `.ssh/config` with their own hosts, survives a re-sync. The
 * generators are pure string functions on purpose — the tests exercise them without
 * a rootfs, and the writer is a thin shell around them.
 *
 * A stored passphrase is deliberately NOT projected into the sandbox: ssh has no way
 * to consume one without either an agent or an askpass helper, and both would mean
 * writing the passphrase (or a script containing it) to a file that outlives the
 * command. A passphrase-protected key therefore prompts once in the terminal, and
 * `ssh-add`/an agent is the supported way to avoid that.
 */
data class GitVaultSyncResult(
    val home: String,
    val tokens: Int,
    val keys: Int,
    val skipped: Int,
    /**
     * Enabled TOKEN entries whose host is a wildcard. They authenticate the API
     * tools (which match the host themselves) but cannot be expressed in
     * `~/.git-credentials`, because git's `store` helper matches literal hosts —
     * so shell `git` needs an exact-host entry. Counted here so the UI can say so
     * instead of silently leaving a clone unauthenticated.
     */
    val wildcards: Int,
)

object GitVaultMaterializer {

    const val MARKER_START = "# >>> minis-git-vault"
    const val MARKER_END = "# <<< minis-git-vault"

    /** Sandbox home: proot runs with `-w /root` and `-r <rootfs>`, so this is `/root`. */
    private const val GUEST_HOME = "/root"

    /** Key filenames are derived from the entry id; this is the shared prefix. */
    private const val KEY_PREFIX = "id_minis_"

    // ─── pure generators ───────────────────────────────────────────────────────

    /** SSH key file name for an entry, relative to `~/.ssh`. */
    fun keyFileName(entry: GitVaultEntry): String =
        KEY_PREFIX + entry.id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * `~/.git-credentials` body. Empty when no token entries are enabled.
     *
     * The user name defaults per host because git sends it verbatim and forges
     * disagree: GitLab wants `oauth2` for a project/personal access token, Bitbucket
     * app passwords want `x-token-auth`, everyone else accepts `git` (GitHub ignores
     * it entirely for a PAT).
     */
    fun credentialsFile(pairs: List<Pair<GitVaultEntry, String>>): String {
        val lines = pairs
            .filter { it.first.kind == GitVaultKind.TOKEN && it.first.enabled && it.second.isNotEmpty() }
            // Wildcards are skipped: git matches `store` lines by literal host, so
            // `https://git:tok@*.corp.example` would never match anything. [sync]
            // reports the count instead.
            .filter { '*' !in it.first.host }
            .map { (entry, token) ->
                "https://${userFor(entry)}:${encodeSecret(token)}@${entry.host}"
            }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * `~/.gitconfig` body: just the credential helper, inside markers, so it can be
     * merged into a file that already has `[user]` and `[alias]` sections. `store`
     * is the helper that reads [credentialsFile].
     */
    fun gitConfigBlock(): String = buildString {
        appendLine("[credential]")
        appendLine("\thelper = store")
    }

    /**
     * `~/.ssh/config` block for every enabled SSH key.
     *
     * `HostName` is only written for a pattern without a wildcard: `Host *.corp.example`
     * plus `HostName *.corp.example` would be nonsense (ssh would literally try to
     * resolve the asterisk), while for `git.corp.example` it is redundant but
     * harmless and lets a user point a short alias at the real host.
     */
    fun sshConfigBlock(pairs: List<Pair<GitVaultEntry, String>>, keyFiles: Map<String, String>): String {
        val blocks = pairs
            .filter { it.first.kind == GitVaultKind.SSH_KEY && it.first.enabled && it.second.isNotEmpty() }
            .map { (entry, _) ->
                val file = keyFiles[entry.id] ?: keyFileName(entry)
                buildString {
                    appendLine("Host ${entry.host}")
                    if ('*' !in entry.host) appendLine("  HostName ${entry.host}")
                    appendLine("  User ${entry.username.ifBlank { "git" }}")
                    appendLine("  IdentityFile ~/.ssh/$file")
                    // Without IdentitiesOnly, ssh offers every key it can find — with
                    // several vault keys for one host (personal + deploy) that trips
                    // the server's auth limit before the right key is tried.
                    appendLine("  IdentitiesOnly yes")
                }
            }
        if (blocks.isEmpty()) return ""
        return blocks.joinToString("")
    }

    /**
     * Replace the marked block in [original] with [block], preserving everything the
     * user wrote around it. [block] empty removes ours outright. A file with no
     * markers gets the block appended (with a trailing newline kept sane).
     */
    fun mergeMarked(original: String, block: String): String {
        val start = original.indexOf(MARKER_START)
        val end = original.indexOf(MARKER_END)
        val hasBlock = start >= 0 && end > start
        val head = if (hasBlock) original.substring(0, start).trimEnd('\n') else original.trimEnd('\n')
        val tail = if (hasBlock) original.substring(end + MARKER_END.length).trimStart('\n') else ""
        val mine = if (block.isEmpty()) "" else "$MARKER_START\n${block.trimEnd('\n')}\n$MARKER_END"
        val parts = listOfNotNull(
            head.ifEmpty { null },
            mine.ifEmpty { null },
            tail.ifEmpty { null },
        )
        return if (parts.isEmpty()) "" else parts.joinToString("\n\n") + "\n"
    }

    /** Bitbucket app passwords are the one place a username must not be `git`. */
    fun userFor(entry: GitVaultEntry): String {
        if (entry.username.isNotBlank()) return entry.username.trim()
        return when {
            entry.host.endsWith("bitbucket.org") -> "x-token-auth"
            entry.host.endsWith("gitlab.com") -> "oauth2"
            else -> "git"
        }
    }

    /**
     * Percent-encode the characters that would otherwise break the URL grammar in
     * `https://user:token@host`. Tokens legitimately contain `+`, `/`, `=` (base64)
     * and occasionally `@` or `:` (pasted app passwords), all of which are fine
     * percent-encoded and fatal raw.
     */
    fun encodeSecret(secret: String): String = buildString {
        for (ch in secret) {
            val safe = ch.isLetterOrDigit() ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~'
            if (safe) append(ch)
            else append('%').append("%02X".format(ch.code and 0xFF))
        }
    }

    // ─── writer ────────────────────────────────────────────────────────────────

    /**
     * Materialize the vault under `<rootfsDir>/root` and return what was written.
     *
     * Reads every secret once, so a vault with 20 entries does one keystore pass
     * rather than 20. Entries without a secret are skipped (a user can save a host
     * first and paste the token later) and counted in [GitVaultSyncResult.skipped].
     */
    fun sync(context: Context?, rootfsDir: File): GitVaultSyncResult {
        if (context == null) return GitVaultSyncResult(GUEST_HOME, 0, 0, 0, 0)
        val entries = GitVault.entries(context)
        return syncEntries(entries.map { it to GitVault.secretFor(context, it.id) }, rootfsDir)
    }

    /**
     * The writer proper, taking the entries and their secrets directly so the on-disk
     * layout (which files, which markers, which lines) is testable without a Context,
     * a keystore or an Android runtime. [sync] is the thin Context-reading shell.
     */
    fun syncEntries(
        entriesWithSecrets: List<Pair<GitVaultEntry, String>>,
        rootfsDir: File,
    ): GitVaultSyncResult {
        val home = File(rootfsDir, GUEST_HOME.trimStart('/'))
        if (entriesWithSecrets.isEmpty()) {
            // Nothing configured: still clear our own blocks so a removed entry stops
            // authenticating. Foreign content is untouched.
            clear(home)
            return GitVaultSyncResult(GUEST_HOME, 0, 0, 0, 0)
        }
        val withSecrets = entriesWithSecrets
        val skipped = withSecrets.count { it.first.enabled && it.second.isEmpty() }

        val tokens = withSecrets.count { it.first.kind == GitVaultKind.TOKEN && it.first.enabled && it.second.isNotEmpty() }
        val keys = withSecrets.count { it.first.kind == GitVaultKind.SSH_KEY && it.first.enabled && it.second.isNotEmpty() }
        val wildcards = withSecrets.count {
            it.first.kind == GitVaultKind.TOKEN && it.first.enabled && it.second.isNotEmpty() && '*' in it.first.host
        }

        val credentials = credentialsFile(withSecrets)
        val credentialsTarget = File(home, ".git-credentials")
        // Deleting rather than leaving an empty file: `store` would read it and find
        // nothing either way, but an empty credential file after a user removed their
        // last token reads as "still configured" when it is not.
        if (credentials.isEmpty()) credentialsTarget.delete()
        else writeFile(credentialsTarget, credentials, ownerOnly = true)

        val gitConfig = File(home, ".gitconfig")
        val gitBlock = if (tokens - wildcards > 0) gitConfigBlock() else ""
        writeFile(gitConfig, mergeMarked(readOrEmpty(gitConfig), gitBlock), ownerOnly = false)

        val sshDir = File(home, ".ssh")
        if (keys > 0) {
            sshDir.mkdirs()
            sshDir.setReadable(false, false)
            sshDir.setReadable(true, true)
            sshDir.setWritable(false, false)
            sshDir.setWritable(true, true)
            sshDir.setExecutable(false, false)
            sshDir.setExecutable(true, true)
        }

        val keyFiles = mutableMapOf<String, String>()
        for ((entry, secret) in withSecrets) {
            if (entry.kind != GitVaultKind.SSH_KEY || !entry.enabled || secret.isEmpty()) continue
            val name = keyFileName(entry)
            keyFiles[entry.id] = name
            // OpenSSH demands a trailing newline; a key pasted from a file picker or a
            // web textarea often arrives without one and ssh refuses it outright.
            val body = if (secret.endsWith("\n")) secret else secret + "\n"
            writeFile(File(sshDir, name), body, ownerOnly = true)
        }

        val sshConfig = File(sshDir, "config")
        writeFile(sshConfig, mergeMarked(readOrEmpty(sshConfig), sshConfigBlock(withSecrets, keyFiles)), ownerOnly = true)

        return GitVaultSyncResult(GUEST_HOME, tokens, keys, skipped, wildcards)
    }

    /** Remove our blocks (and our key files) without touching anything else. */
    private fun clear(home: File) {
        val gitConfig = File(home, ".gitconfig")
        if (gitConfig.exists()) writeFile(gitConfig, mergeMarked(readOrEmpty(gitConfig), ""), ownerOnly = false)
        val sshDir = File(home, ".ssh")
        val sshConfig = File(sshDir, "config")
        if (sshConfig.exists()) writeFile(sshConfig, mergeMarked(readOrEmpty(sshConfig), ""), ownerOnly = true)
        if (sshDir.isDirectory) {
            sshDir.listFiles { f -> f.name.startsWith(KEY_PREFIX) }?.forEach { it.delete() }
        }
        File(home, ".git-credentials").delete()
    }

    private fun readOrEmpty(file: File): String =
        runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")

    /**
     * Write [content] with the perms git/ssh insist on. `ownerOnly` uses the
     * Android `File` permission API: a world-readable private key or
     * `.git-credentials` is exactly the leak this feature would otherwise create.
     */
    private fun writeFile(file: File, content: String, ownerOnly: Boolean) {
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        if (ownerOnly) file.setExecutable(false, false)
    }
}
