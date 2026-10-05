package com.openminis.app.data.gitvault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-git-vault] The vault's pure half: host matching, the generated sandbox files,
 * and the legacy import plan. Nothing here touches a Context, a keystore or a
 * rootfs — the writers are thin shells around these functions on purpose.
 *
 * The rules pinned below are the ones that fail SILENTLY in production:
 *  - a wildcard that outranks an exact host would send the wrong token to the wrong
 *    server (and a wrong token can lock the account, not just 401);
 *  - a `/root/.git-credentials` line built with a raw token breaks on `@` or `/`,
 *    and git then prompts forever instead of failing loudly;
 *  - an `.ssh/config` merge that drops the user's own hosts, or an `IdentityFile`
 *    that is world-readable, are exactly the accidents this feature must not cause.
 */
class GitVaultTest {

    private fun entry(
        id: String = "e1",
        host: String,
        kind: GitVaultKind = GitVaultKind.TOKEN,
        username: String = "",
        note: String = "",
        enabled: Boolean = true,
    ) = GitVaultEntry(id = id, host = host, kind = kind, username = username, note = note, enabled = enabled)

    // ── matching ────────────────────────────────────────────────────────────

    @Test
    fun `exact host beats wildcard beats catch-all`() {
        val entries = listOf(
            entry(id = "any", host = "*"),
            entry(id = "wild", host = "*.corp.example"),
            entry(id = "exact", host = "git.corp.example"),
        )
        assertEquals("exact", GitVault.match(entries, "git.corp.example", GitVaultKind.TOKEN)?.first?.id)
        assertEquals("wild", GitVault.match(entries, "other.corp.example", GitVaultKind.TOKEN)?.first?.id)
        assertEquals("any", GitVault.match(entries, "github.com", GitVaultKind.TOKEN)?.first?.id)
    }

    @Test
    fun `a wildcard covers subdomains but not the apex domain`() {
        // "*.corp.example" must not match "corp.example": the token was scoped to
        // hosts below the domain, and the apex is frequently a different server.
        assertNull(GitVault.patternScore("*.corp.example", "corp.example"))
        assertNull(GitVault.patternScore("*.corp.example", "notcorp.example"))
        assertEquals(5_000 + "*.corp.example".length, GitVault.patternScore("*.corp.example", "git.corp.example"))
        assertEquals(5_000 + "*.corp.example".length, GitVault.patternScore("*.corp.example", "a.b.corp.example"))
    }

    @Test
    fun `a bare domain pattern also covers its subdomains`() {
        // Self-hosted forges usually answer on git.<domain> while the token is
        // issued for the domain, so an entry written as "corp.example" has to cover
        // "git.corp.example" — and must not leak to "evilcorp.example".
        assertEquals(3_000 + "corp.example".length, GitVault.patternScore("corp.example", "git.corp.example"))
        assertNull(GitVault.patternScore("corp.example", "evilcorp.example"))
        assertEquals(10_000 + "corp.example".length, GitVault.patternScore("corp.example", "corp.example"))
    }

    @Test
    fun `the longer wildcard wins when two overlap`() {
        val entries = listOf(
            entry(id = "broad", host = "*.example.com"),
            entry(id = "narrow", host = "*.ci.example.com"),
        )
        assertEquals("narrow", GitVault.match(entries, "build.ci.example.com", GitVaultKind.TOKEN)?.first?.id)
        assertEquals("broad", GitVault.match(entries, "www.example.com", GitVaultKind.TOKEN)?.first?.id)
    }

    @Test
    fun `kind breaks a tie between a token and an ssh key on the same host`() {
        val entries = listOf(
            entry(id = "key", host = "git.corp.example", kind = GitVaultKind.SSH_KEY),
            entry(id = "token", host = "git.corp.example", kind = GitVaultKind.TOKEN),
        )
        assertEquals("token", GitVault.match(entries, "git.corp.example", GitVaultKind.TOKEN)?.first?.id)
        assertEquals("key", GitVault.match(entries, "git.corp.example", GitVaultKind.SSH_KEY)?.first?.id)
    }

    @Test
    fun `the match reports the host score, not the kind bonus`() {
        val entries = listOf(entry(id = "token", host = "git.corp.example"))
        val (_, score) = GitVault.match(entries, "git.corp.example", GitVaultKind.TOKEN)!!
        assertEquals(10_000 + "git.corp.example".length, score)
    }

    @Test
    fun `a disabled entry never matches, and the next rule takes over`() {
        val entries = listOf(
            entry(id = "off", host = "git.corp.example", enabled = false),
            entry(id = "wild", host = "*.corp.example"),
        )
        assertEquals("wild", GitVault.match(entries, "git.corp.example", GitVaultKind.TOKEN)?.first?.id)
    }

    @Test
    fun `hosts are compared without scheme, port, user or trailing dot`() {
        val entries = listOf(entry(host = "git.corp.example", kind = GitVaultKind.TOKEN))
        for (probe in listOf(
            "git.corp.example",
            "GIT.Corp.Example",
            "git.corp.example:2222",
            "git.corp.example.",
            "ssh://git@git.corp.example:2222/team/app.git",
            "https://git.corp.example/team/app",
            "git@git.corp.example:team/app.git",
        )) {
            assertEquals(probe, "e1", GitVault.match(entries, probe, GitVaultKind.TOKEN)?.first?.id)
        }
    }

    @Test
    fun `hostOf accepts the forms a user pastes and rejects a bare owner slash repo`() {
        assertEquals("github.com", GitVault.hostOf("https://github.com/owner/repo"))
        assertEquals("gitlab.com", GitVault.hostOf("https://user:pass@gitlab.com:8443/g/p"))
        assertEquals("git.corp.example", GitVault.hostOf("ssh://git@git.corp.example/team/app.git"))
        assertEquals("git.corp.example", GitVault.hostOf("git@git.corp.example:team/app.git"))
        assertEquals("git.corp.example", GitVault.hostOf("git.corp.example:2222/team/app"))
        assertEquals("git.corp.example", GitVault.hostOf("git.corp.example/team/app"))
        // The most common bare form has no host: reading "owner" as a host would let
        // a "*" entry swallow every shorthand the user types.
        assertNull(GitVault.hostOf("owner/repo"))
        assertNull(GitVault.hostOf(""))
    }

    @Test
    fun `normalizePattern turns a pasted url into a pattern`() {
        assertEquals("git.corp.example", GitVault.normalizePattern("https://git.corp.example/team/app.git"))
        assertEquals("github.com", GitVault.normalizePattern("  GitHub.com "))
        assertEquals("git.corp.example", GitVault.normalizePattern("git@git.corp.example:team/app"))
        assertEquals("*.corp.example", GitVault.normalizePattern("*.corp.example"))
        assertEquals("*", GitVault.normalizePattern("*"))
        assertEquals("", GitVault.normalizePattern("   "))
    }

    // ── legacy import ───────────────────────────────────────────────────────

    @Test
    fun `legacy import seeds one entry per host and skips taken or empty ones`() {
        val plan = GitVault.legacyImportPlan(
            existing = listOf(entry(host = "gitlab.com")),
            legacy = listOf(
                "https://github.com" to "gh-token",
                "gitlab.com" to "gl-token",
                "gitea.example" to "",
                "codeberg.org" to "cb-token",
                "codeberg.org" to "duplicate",
            ),
        )
        assertEquals(listOf("github.com", "codeberg.org"), plan.map { it.first.host })
        assertEquals(listOf("gh-token", "cb-token"), plan.map { it.second })
        assertTrue(plan.all { it.first.kind == GitVaultKind.TOKEN })
        assertEquals(plan.size, plan.map { it.first.id }.toSet().size)
    }

    // ── generated sandbox files ─────────────────────────────────────────────

    @Test
    fun `git-credentials uses the forge-specific default user`() {
        val file = GitVaultMaterializer.credentialsFile(
            listOf(
                entry(id = "gh", host = "github.com") to "ghp_x",
                entry(id = "gl", host = "gitlab.com") to "glpat-y",
                entry(id = "bb", host = "bitbucket.org") to "app-pass",
                entry(id = "self", host = "git.corp.example") to "tok",
            ),
        )
        assertEquals(
            listOf(
                "https://git:ghp_x@github.com",
                "https://oauth2:glpat-y@gitlab.com",
                "https://x-token-auth:app-pass@bitbucket.org",
                "https://git:tok@git.corp.example",
            ),
            file.trim().lines(),
        )
    }

    @Test
    fun `an explicit username wins over the per-host default`() {
        val file = GitVaultMaterializer.credentialsFile(
            listOf(entry(host = "git.corp.example", username = "ci-bot") to "tok"),
        )
        assertEquals("https://ci-bot:tok@git.corp.example", file.trim())
    }

    @Test
    fun `tokens are percent-encoded so punctuation cannot break the url`() {
        // App passwords and base64 tokens routinely contain these; raw, git would
        // parse "@host" differently or prompt forever.
        assertEquals("a%40b%3Ac%2Bd%2Fe%3Df", GitVaultMaterializer.encodeSecret("a@b:c+d/e=f"))
        assertEquals("abc-._~123", GitVaultMaterializer.encodeSecret("abc-._~123"))
        val file = GitVaultMaterializer.credentialsFile(
            listOf(entry(host = "bitbucket.org") to "user@example.com:secret/with+chars="),
        )
        assertEquals(
            "https://x-token-auth:user%40example.com%3Asecret%2Fwith%2Bchars%3D@bitbucket.org",
            file.trim(),
        )
    }

    @Test
    fun `wildcard, disabled and secretless token entries are left out of the file`() {
        val file = GitVaultMaterializer.credentialsFile(
            listOf(
                entry(id = "wild", host = "*.corp.example") to "tok",
                entry(id = "off", host = "github.com", enabled = false) to "tok",
                entry(id = "empty", host = "codeberg.org") to "",
                entry(id = "keep", host = "gitlab.com") to "glpat",
            ),
        )
        // git's `store` helper matches literal hosts, so a wildcard line would never
        // be used; writing it anyway would just look like it works.
        assertEquals(listOf("https://oauth2:glpat@gitlab.com"), file.trim().lines())
        assertEquals("", GitVaultMaterializer.credentialsFile(emptyList()))
    }

    @Test
    fun `ssh config pins the identity file and stops ssh offering every key`() {
        val block = GitVaultMaterializer.sshConfigBlock(
            pairs = listOf(
                entry(id = "a", host = "git.corp.example", kind = GitVaultKind.SSH_KEY) to "KEY",
                entry(id = "b", host = "*.ci.example", kind = GitVaultKind.SSH_KEY, username = "deploy") to "KEY2",
                entry(id = "c", host = "github.com", kind = GitVaultKind.TOKEN) to "tok",
            ),
            keyFiles = emptyMap(),
        )
        assertTrue(block, block.contains("Host git.corp.example\n  HostName git.corp.example\n  User git\n  IdentityFile ~/.ssh/id_minis_a\n  IdentitiesOnly yes"))
        // A wildcard must not become HostName: ssh would try to resolve the asterisk.
        assertTrue(block, block.contains("Host *.ci.example\n  User deploy\n  IdentityFile ~/.ssh/id_minis_b\n  IdentitiesOnly yes"))
        assertFalse(block, block.contains("HostName *.ci.example"))
        assertFalse("token entries are not ssh hosts", block.contains("Host github.com"))
        assertEquals("", GitVaultMaterializer.sshConfigBlock(emptyList(), emptyMap()))
    }

    @Test
    fun `key file names come from the id so two keys for one host never collide`() {
        val one = GitVaultMaterializer.keyFileName(entry(id = "11111111-2222", host = "git.corp.example"))
        val two = GitVaultMaterializer.keyFileName(entry(id = "99999999-8888", host = "git.corp.example"))
        assertEquals("id_minis_11111111-2222", one)
        assertNotEquals(one, two)
        // An id is a UUID today, but a sanitizer keeps a hand-written or migrated id
        // from escaping the .ssh directory.
        assertEquals("id_minis_a_b", GitVaultMaterializer.keyFileName(entry(id = "a/b", host = "h")))
    }

    @Test
    fun `gitconfig block only sets the store helper`() {
        assertEquals(
            listOf("[credential]", "\thelper = store"),
            GitVaultMaterializer.gitConfigBlock().trim().lines(),
        )
    }

    // ── marker merge ────────────────────────────────────────────────────────

    @Test
    fun `merge appends our block to a file that has none`() {
        val merged = GitVaultMaterializer.mergeMarked("[user]\n\tname = Ada\n", GitVaultMaterializer.gitConfigBlock())
        assertTrue(merged.startsWith("[user]\n\tname = Ada\n"))
        assertTrue(merged.contains(GitVaultMaterializer.MARKER_START))
        assertTrue(merged.endsWith(GitVaultMaterializer.MARKER_END + "\n"))
        assertTrue(merged.contains("\thelper = store"))
    }

    @Test
    fun `merge replaces only our block and is idempotent`() {
        val first = GitVaultMaterializer.mergeMarked(
            "[user]\n\tname = Ada\n[alias]\n\tco = checkout\n",
            GitVaultMaterializer.gitConfigBlock(),
        )
        val second = GitVaultMaterializer.mergeMarked(first, "[credential]\n\thelper = cache\n")
        assertEquals(1, Regex(Regex.escape(GitVaultMaterializer.MARKER_START)).findAll(second).count())
        assertTrue("user config survives", second.contains("\tname = Ada"))
        assertTrue("sections after ours survive", second.contains("co = checkout"))
        assertTrue("no store helper is left behind", !second.contains("helper = store"))
        // Re-merging the same block changes nothing, so a user re-syncing twice does
        // not accumulate duplicate sections or blank lines.
        assertEquals(second, GitVaultMaterializer.mergeMarked(second, "[credential]\n\thelper = cache\n"))
    }

    @Test
    fun `an empty block removes ours and keeps everything else`() {
        val withBlock = GitVaultMaterializer.mergeMarked("[user]\n\tname = Ada\n", GitVaultMaterializer.gitConfigBlock())
        val cleared = GitVaultMaterializer.mergeMarked(withBlock, "")
        assertEquals("[user]\n\tname = Ada\n", cleared)
        assertEquals("", GitVaultMaterializer.mergeMarked("", ""))
    }

    // ── the writer, on a real directory ─────────────────────────────────────
    //
    // The generators above are pure, but what a user actually depends on is the FILE
    // LAYOUT: which file, which markers, whether a re-sync duplicates a section, and
    // whether removing the last entry really stops the credential authenticating.
    // A temp dir exercises all of it without a Context, keystore or Android runtime.

    private fun tempRootfs(): File =
        java.nio.file.Files.createTempDirectory("gitvault-test").toFile()

    private fun token(id: String, host: String, secret: String, username: String = "") =
        entry(id = id, host = host, kind = GitVaultKind.TOKEN, username = username) to secret

    private fun ssh(id: String, host: String, secret: String) =
        entry(id = id, host = host, kind = GitVaultKind.SSH_KEY) to secret

    @Test
    fun `sync writes the credentials file, the helper and the ssh config`() {
        val rootfs = tempRootfs()
        val result = GitVaultMaterializer.syncEntries(
            listOf(
                token("t1", "gitlab.com", "glpat-x"),
                ssh("k1", "git.corp.example", "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"),
            ),
            rootfs,
        )
        assertEquals(1, result.tokens)
        assertEquals(1, result.keys)
        assertEquals(0, result.skipped)

        val home = File(rootfs, "root")
        assertEquals(
            "https://oauth2:glpat-x@gitlab.com\n",
            File(home, ".git-credentials").readText(),
        )
        // The helper that makes git READ the credentials file has to be there too;
        // without it the file is inert.
        val gitConfig = File(home, ".gitconfig").readText()
        assertTrue(gitConfig, gitConfig.contains("helper = store"))
        assertTrue(gitConfig, gitConfig.contains(GitVaultMaterializer.MARKER_START))

        val sshConfig = File(home, ".ssh/config").readText()
        assertTrue(sshConfig, sshConfig.contains("Host git.corp.example"))
        assertTrue(sshConfig, sshConfig.contains("IdentityFile ~/.ssh/id_minis_k1"))
        assertTrue(sshConfig, sshConfig.contains("IdentitiesOnly yes"))
        // ssh refuses a key with no trailing newline, and a key pasted from a textarea
        // usually has none.
        val key = File(home, ".ssh/id_minis_k1").readText()
        assertTrue(key, key.endsWith("-----END OPENSSH PRIVATE KEY-----\n"))
        rootfs.deleteRecursively()
    }

    @Test
    fun `sync preserves foreign config and is idempotent`() {
        val rootfs = tempRootfs()
        val home = File(rootfs, "root")
        File(home, ".ssh").mkdirs()
        File(home, ".gitconfig").writeText("[user]\n\tname = Ada\n")
        File(home, ".ssh/config").writeText("Host my-own-box\n  HostName 10.0.0.9\n")

        val pairs = listOf(token("t1", "github.com", "ghp_a"), ssh("k1", "git.corp.example", "KEY"))
        GitVaultMaterializer.syncEntries(pairs, rootfs)
        GitVaultMaterializer.syncEntries(pairs, rootfs)

        val gitConfig = File(home, ".gitconfig").readText()
        assertTrue(gitConfig, gitConfig.contains("name = Ada"))
        assertEquals(1, Regex(Regex.escape(GitVaultMaterializer.MARKER_START)).findAll(gitConfig).count())
        val sshConfig = File(home, ".ssh/config").readText()
        assertTrue("the user's own host survives", sshConfig.contains("Host my-own-box"))
        assertEquals(1, Regex("Host git\\.corp\\.example").findAll(sshConfig).count())
        rootfs.deleteRecursively()
    }

    @Test
    fun `an empty vault clears our blocks and key files but nothing else`() {
        val rootfs = tempRootfs()
        val home = File(rootfs, "root")
        File(home, ".ssh").mkdirs()
        File(home, ".gitconfig").writeText("[user]\n\tname = Ada\n")
        GitVaultMaterializer.syncEntries(
            listOf(token("t1", "github.com", "ghp_a"), ssh("k1", "git.corp.example", "KEY")),
            rootfs,
        )
        // The user removes every entry. Leaving the files behind would keep the
        // terminal authenticating with a credential the user believes is gone.
        GitVaultMaterializer.syncEntries(emptyList(), rootfs)
        assertFalse("credential file is gone", File(home, ".git-credentials").exists())
        assertFalse("key material is gone", File(home, ".ssh/id_minis_k1").exists())
        assertTrue("the user's own gitconfig survives", File(home, ".gitconfig").readText().contains("name = Ada"))
        assertFalse(
            "our helper is gone with it",
            File(home, ".gitconfig").readText().contains("helper = store"),
        )
        assertFalse(File(home, ".ssh/config").readText().contains("git.corp.example"))
        rootfs.deleteRecursively()
    }

    @Test
    fun `entries without a secret are counted, not written`() {
        val rootfs = tempRootfs()
        val result = GitVaultMaterializer.syncEntries(
            listOf(
                token("t1", "github.com", ""),
                token("t2", "gitlab.com", "glpat-x"),
                token("t3", "*.corp.example", "wild"),
            ),
            rootfs,
        )
        assertEquals(1, result.skipped)
        assertEquals(2, result.tokens)
        assertEquals(1, result.wildcards)
        assertEquals(
            "https://oauth2:glpat-x@gitlab.com\n",
            File(File(rootfs, "root"), ".git-credentials").readText(),
        )
        rootfs.deleteRecursively()
    }

    // ── display ─────────────────────────────────────────────────────────────

    @Test
    fun `masking never reveals a middle of the secret`() {
        // 18 chars → first 2 + 14 stars + last 2.
        assertEquals("gh" + "*".repeat(14) + "yz", GitVault.masked("ghp_1234567890wxyz"))
        assertEquals("ab****gh", GitVault.masked("abcdefgh"))
        assertEquals("*******", GitVault.masked("abcdefg"))
        assertEquals("***", GitVault.masked("abc"))
        assertEquals("", GitVault.masked(""))
        // The masked form must never contain the full secret.
        val secret = "ghp_abcdefghijklmnop"
        assertFalse(GitVault.masked(secret).contains(secret))
    }
}
