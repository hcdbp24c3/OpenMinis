package com.openminis.app.data.gitvault

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * [T-git-vault] One credential store for every git host: HTTPS tokens AND SSH keys,
 * matched to the host the user actually names.
 *
 * Why a vault instead of a field per host: the first cut of [com.openminis.app.tools.RepoDigestTool]
 * had one token slot per forge family (GitHub / GitLab / Gitea / Bitbucket), which
 * already broke down — a company GitLab and gitlab.com want DIFFERENT tokens, a
 * second GitHub Enterprise host is a second token, and SSH keys had nowhere to go
 * at all. Entries here are keyed by HOST PATTERN instead, so "git.corp.example"
 * and "gitlab.com" resolve independently, and a wildcard ("*.corp.example") covers
 * a fleet of self-hosted forges without an entry each.
 *
 * Two consumers, one store:
 *  - [com.openminis.app.tools.RepoDigestTool] asks for the HTTPS token of the host
 *    it is about to call (the header shape stays provider-driven: Bearer /
 *    `PRIVATE-TOKEN` / `token …`).
 *  - [GitVaultMaterializer] projects the vault into the sandbox (`~/.git-credentials`,
 *    `~/.ssh/config`, `~/.ssh/id_*`) so plain `git clone`/`git push` in the terminal
 *    authenticate without the user exporting anything.
 *
 * Storage split: hosts, labels and kinds are not secrets, so they live in plain
 * prefs (cheap to list, and a keystore reset can never hide the user's host list).
 * Secrets — token text, key material, key passphrase — live in an
 * [EncryptedPrefsFactory] store keyed by entry id, so an entry can be edited (host,
 * note) without ever rewriting its secret, and a deleted entry drops its secret.
 *
 * Nothing in this file logs or returns a secret except [secretFor], and no caller
 * puts one in tool output.
 */
enum class GitVaultKind {
    /** HTTPS credential: a personal access token / app password. */
    TOKEN,

    /** SSH private key (OpenSSH or PEM), optionally passphrase-protected. */
    SSH_KEY,
}

@Serializable
data class GitVaultEntry(
    val id: String,
    /**
     * Host pattern. `github.com` (exact), `*.corp.example` (any subdomain), or `*`
     * (catch-all, lowest priority). Never contains a scheme, port or path — paste
     * whatever you like into the editor and [GitVault.normalizePattern] strips it.
     *
     * A catch-all is a deliberate, dangerous choice and the editor says so: matching
     * `*` means the secret is sent to ANY host a tool call names, so a model talked
     * into digesting an attacker's repository would hand it over. Exact hosts and
     * wildcards are the safe forms; migration never creates a `*` entry.
     */
    val host: String,
    val kind: GitVaultKind,
    /**
     * HTTPS username. Usually left empty and defaulted per host at write time
     * (`git` for most forges, `oauth2` for GitLab, `x-token-auth` for Bitbucket
     * app passwords); SSH blocks always use `git`.
     */
    val username: String = "",
    /** Free-form label shown in the list ("work GitLab", "deploy key"). */
    val note: String = "",
    val enabled: Boolean = true,
)

object GitVault {

    /** Plain (non-secret) metadata. */
    const val PREFS = "git_vault"

    /** Encrypted secrets, created through the self-healing keystore factory. */
    const val SECRETS = "git_vault_secrets"

    private const val KEY_ENTRIES = "entries"

    /**
     * Import marker: the one-shot migration from the old per-forge token fields
     * runs once, so a user who deletes an imported entry doesn't get it resurrected
     * on every launch.
     */
    private const val KEY_LEGACY_IMPORTED = "legacy_imported"

    private val json = Json { ignoreUnknownKeys = true }

    // ─── metadata ──────────────────────────────────────────────────────────────

    fun entries(context: Context?): List<GitVaultEntry> {
        if (context == null) return emptyList()
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<GitVaultEntry>>(raw) }.getOrDefault(emptyList())
    }

    /** Insert or update by [GitVaultEntry.id]. Order is preserved; new entries append. */
    fun save(context: Context, entry: GitVaultEntry): List<GitVaultEntry> {
        val normalized = entry.copy(host = normalizePattern(entry.host))
        val current = entries(context)
        val next = if (current.any { it.id == normalized.id }) {
            current.map { if (it.id == normalized.id) normalized else it }
        } else {
            current + normalized
        }
        write(context, next)
        return next
    }

    /** Add an entry with a fresh id (the editor's "add" path). */
    fun add(
        context: Context,
        host: String,
        kind: GitVaultKind,
        username: String = "",
        note: String = "",
    ): GitVaultEntry {
        val entry = GitVaultEntry(
            id = UUID.randomUUID().toString(),
            host = normalizePattern(host),
            kind = kind,
            username = username.trim(),
            note = note.trim(),
        )
        write(context, entries(context) + entry)
        return entry
    }

    fun delete(context: Context, id: String): List<GitVaultEntry> {
        val next = entries(context).filterNot { it.id == id }
        write(context, next)
        secrets(context).edit().remove(secretKey(id)).remove(passphraseKey(id)).apply()
        return next
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean): List<GitVaultEntry> {
        val next = entries(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        write(context, next)
        return next
    }

    private fun write(context: Context, list: List<GitVaultEntry>) {
        prefs(context).edit().putString(KEY_ENTRIES, json.encodeToString(list)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ─── secrets ───────────────────────────────────────────────────────────────

    private fun secretKey(id: String) = "secret_$id"

    private fun passphraseKey(id: String) = "pass_$id"

    private fun secrets(context: Context) =
        EncryptedPrefsFactory.safeCreate(context.applicationContext, SECRETS)

    fun setSecret(context: Context, id: String, value: String) {
        secrets(context).edit().putString(secretKey(id), value).apply()
    }

    fun setPassphrase(context: Context, id: String, value: String) {
        val store = secrets(context)
        if (value.isEmpty()) store.edit().remove(passphraseKey(id)).apply()
        else store.edit().putString(passphraseKey(id), value).apply()
    }

    /** Token text or private-key material. Empty when unset. */
    fun secretFor(context: Context?, id: String): String {
        if (context == null) return ""
        return secrets(context).getString(secretKey(id), null).orEmpty()
    }

    /** SSH key passphrase, or empty for an unencrypted key. */
    fun passphraseFor(context: Context?, id: String): String {
        if (context == null) return ""
        return secrets(context).getString(passphraseKey(id), null).orEmpty()
    }

    fun hasSecret(context: Context?, id: String): Boolean = secretFor(context, id).isNotEmpty()

    // ─── matching ──────────────────────────────────────────────────────────────

    /**
     * Priority of a pattern against a host: higher wins. Null when it does not match.
     *
     * Exact beats wildcard beats `*`, and a longer pattern beats a shorter one, so
     * `git.corp.example` wins over `*.corp.example`, which wins over `*`. The host
     * is compared WITHOUT port/userinfo (see [normalizeHost]) — a vault entry says
     * `git.corp.example`, not `git.corp.example:2222`, because the same host is
     * reached on 22 for SSH and 443 for HTTPS.
     */
    fun patternScore(pattern: String, host: String): Int? {
        val p = normalizePattern(pattern)
        val h = normalizeHost(host)
        if (p.isEmpty() || h.isEmpty()) return null
        return when {
            p == "*" -> 1_000
            p == h -> 10_000 + p.length
            // "*.corp.example" matches "git.corp.example" and "a.b.corp.example".
            // Never "corp.example" itself: the wildcard stands for at least one label.
            p.startsWith("*.") && h.endsWith(p.substring(1)) && h.length > p.length - 1 ->
                5_000 + p.length
            // Bare-domain shorthand: an entry written as "corp.example" also covers
            // its subdomains, because a self-hosted forge usually answers on
            // git.<domain> while the token is issued for the domain as a whole.
            '.' !in p.substringBeforeLast('.') && (h == p || h.endsWith(".$p")) ->
                3_000 + p.length
            else -> null
        }
    }

    /**
     * The entry (and its score) that should authenticate [host], or null.
     *
     * [kind] breaks ties between an HTTPS token and an SSH key configured for the
     * same host: a caller that will speak HTTPS asks for [GitVaultKind.TOKEN] and
     * gets the token entry, while a caller that only finds an SSH key for that host
     * gets null rather than a key it cannot use. Disabled entries never match.
     */
    fun match(entries: List<GitVaultEntry>, host: String, kind: GitVaultKind): Pair<GitVaultEntry, Int>? {
        return entries.asSequence()
            .filter { it.enabled }
            .mapNotNull { entry ->
                val score = patternScore(entry.host, host) ?: return@mapNotNull null
                // Same host, right kind first; a wrong-kind entry still ranks, so a
                // misconfigured vault degrades instead of going silent.
                val kindBonus = if (entry.kind == kind) 500 else 0
                Triple(entry, score + kindBonus, score)
            }
            .maxByOrNull { it.second }
            ?.let { it.first to it.third }
    }

    fun matchFor(context: Context?, host: String, kind: GitVaultKind): GitVaultEntry? =
        match(entries(context), host, kind)?.first

    /** The HTTPS token to send to [host], or "" when the vault has none for it. */
    fun tokenFor(context: Context?, host: String): String {
        val entry = matchFor(context, host, GitVaultKind.TOKEN) ?: return ""
        if (entry.kind != GitVaultKind.TOKEN) return ""
        return secretFor(context, entry.id)
    }

    // ─── host normalisation ────────────────────────────────────────────────────

    /** Lowercase, no scheme/userinfo/port/path, no trailing dot. */
    fun normalizePattern(raw: String): String {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return ""
        // Accept a pasted URL or an scp-style remote as a pattern too.
        hostOf(s)?.let { return it }
        s = s.substringAfter("://").substringBefore('/')
        s = s.substringAfterLast('@')
        s = s.substringBefore(':')
        return s.trimEnd('.')
    }

    /** Lowercase, no userinfo, no port, no trailing dot. */
    fun normalizeHost(raw: String): String {
        val fromUrl = hostOf(raw)
        if (fromUrl != null) return fromUrl
        var s = raw.trim().lowercase()
        s = s.substringAfter("://").substringBefore('/')
        s = s.substringAfterLast('@')
        s = s.substringBefore(':')
        return s.trimEnd('.')
    }

    /**
     * The host inside anything a user might paste: a full URL, an `ssh://` URL, an
     * scp-style `git@host:owner/repo`, a `host/path/to/repo` fragment, or a
     * `host:port/repo`. Returns null for a bare `owner/repo` (no host to match).
     */
    fun hostOf(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // scheme://[user[:pass]@]host[:port][/path]
        Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://(?:[^/@]*@)?([^/:?#]+)""").find(s)?.let {
            return it.groupValues[1].lowercase().trimEnd('.')
        }
        // scp-style: user@host:path (host has a dot or is a known-looking name,
        // path does not start with a digit-only port form ambiguity we can avoid:
        // "git@host:22/repo" would look like scp with port — treat digits+"/" as a port)
        Regex("""^[^/@\s]+@([^:/\s]+):.*$""").find(s)?.let {
            return it.groupValues[1].lowercase().trimEnd('.')
        }
        // host/path or host:port/path, but NOT owner/repo — a host must have a dot or
        // a port, otherwise "owner/repo" (the most common bare form) would be read as
        // the host "owner".
        val head = s.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPart = head.substringBeforeLast(':', missingDelimiterValue = head)
        val looksLikeHost = head.contains('.') && !head.startsWith('.') && !head.endsWith('.')
        if (!looksLikeHost) return null
        val host = if (head.contains(':') && head.substringAfterLast(':').all { it.isDigit() }) hostPart else head
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }

    // ─── legacy migration ──────────────────────────────────────────────────────

    /**
     * The one-shot migration from the per-forge token fields the repo-digest screen
     * used to own (`repo_digest_prefs`, four plain-prefs strings). Called from app
     * start rather than from the tool, so a read tool never writes.
     *
     * Three of the four fields name a host unambiguously and become ordinary
     * enabled entries. The Gitea one does NOT: it was a single field covering
     * "Gitea / Forgejo / Codeberg" with no host at all, and guessing `codeberg.org`
     * would send a self-hosted instance's token to a public forge (and vice versa) —
     * a credential leak, not a convenience. So it is imported DISABLED under
     * `codeberg.org` with a note telling the user to set the real host and enable it,
     * which keeps the value without ever sending it anywhere they did not name.
     */
    fun importRepoDigestLegacyOnce(context: Context) {
        val store = prefs(context)
        if (store.getBoolean(KEY_LEGACY_IMPORTED, false)) return
        val legacy = runCatching {
            val old = context.applicationContext
                .getSharedPreferences("repo_digest_prefs", Context.MODE_PRIVATE)
            listOf(
                "github.com" to old.getString("github_token", null).orEmpty(),
                "gitlab.com" to old.getString("gitlab_token", null).orEmpty(),
                "bitbucket.org" to old.getString("bitbucket_token", null).orEmpty(),
            )
        }.getOrDefault(emptyList())
        importLegacyOnce(context, legacy)

        val gitea = runCatching {
            context.applicationContext
                .getSharedPreferences("repo_digest_prefs", Context.MODE_PRIVATE)
                .getString("gitea_token", null)
                .orEmpty()
                .trim()
        }.getOrDefault("")
        if (gitea.isNotEmpty() && entries(context).none { it.host == "codeberg.org" }) {
            val entry = GitVaultEntry(
                id = UUID.randomUUID().toString(),
                host = "codeberg.org",
                kind = GitVaultKind.TOKEN,
                note = "imported; the old Gitea field had no host — set the real one and enable",
                enabled = false,
            )
            setSecret(context, entry.id, gitea)
            write(context, entries(context) + entry)
        }
        // Belt and braces: importLegacyOnce already set the flag, but a crash between
        // the two writes would otherwise re-import the Gitea value on the next launch.
        store.edit().putBoolean(KEY_LEGACY_IMPORTED, true).apply()
    }

    /**
     * One-shot import of the per-forge token fields the repo-digest screen used to
     * own. Runs once ever ([KEY_LEGACY_IMPORTED]): after that the tokens are vault
     * entries like any other, and a user who removes one is not re-imported.
     *
     * [legacy] is a list of (host, token) pairs supplied by the caller so this stays
     * free of any tool-specific dependency.
     */
    fun importLegacyOnce(context: Context, legacy: List<Pair<String, String>>) {
        val store = prefs(context)
        if (store.getBoolean(KEY_LEGACY_IMPORTED, false)) return
        val plan = legacyImportPlan(entries(context), legacy)
        if (plan.isNotEmpty()) {
            for ((entry, token) in plan) setSecret(context, entry.id, token)
            write(context, entries(context) + plan.map { it.first })
        }
        store.edit().putBoolean(KEY_LEGACY_IMPORTED, true).apply()
    }

    /**
     * The entries (with their secrets) a legacy import would create, given what the
     * vault already holds. Pure so the "already configured, or empty, or a duplicate
     * host" rules are testable without a Context.
     */
    fun legacyImportPlan(
        existing: List<GitVaultEntry>,
        legacy: List<Pair<String, String>>,
    ): List<Pair<GitVaultEntry, String>> {
        val out = mutableListOf<Pair<GitVaultEntry, String>>()
        for ((host, token) in legacy) {
            val value = token.trim()
            val h = normalizePattern(host)
            if (value.isEmpty() || h.isEmpty()) continue
            // A host the user already configured (or a duplicate inside the legacy
            // set) wins: their entry may carry a different kind, note or username.
            val taken = existing.any { it.host == h } || out.any { it.first.host == h }
            if (taken) continue
            out += GitVaultEntry(
                id = UUID.randomUUID().toString(),
                host = h,
                kind = GitVaultKind.TOKEN,
                note = "imported",
            ) to value
        }
        return out
    }

    // ─── display ───────────────────────────────────────────────────────────────

    /**
     * Masked form for the UI: first 2 + asterisks + last 2 for anything long enough
     * to be a real secret, all asterisks below that. Mirrors [com.openminis.app.data.EnvVarRedactor]'s
     * rule so a secret looks the same wherever the app shows it.
     */
    fun masked(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length < 8) return "*".repeat(secret.length)
        return secret.take(2) + "*".repeat(secret.length - 4) + secret.takeLast(2)
    }
}
