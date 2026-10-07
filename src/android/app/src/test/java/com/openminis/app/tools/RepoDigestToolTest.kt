package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-repo-digest] The digest tool's pure half.
 *
 * URL parsing and file selection are where this tool can go quietly wrong: a
 * mis-parsed `/tree/feature/x/src` link fetches the wrong branch and the model
 * reads a plausible-but-wrong file; a selection rule that keeps `node_modules`
 * burns the whole budget on vendored code. Both are pinned here, with the network
 * paths (one tree call + raw fetches) left out on purpose.
 */
class RepoDigestToolTest {

    // ── URL shapes ──────────────────────────────────────────────────────────

    @Test
    fun `parses the shapes a user actually pastes`() {
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", null, null),
            RepoDigestTool.parseRepoUrl("owner/repo"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", null, null),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", null, null),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo.git/"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "src"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/tree/main/src"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "docs/readme.md"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/docs/readme.md?plain=1#L3"),
        )
        // Not a tree/blob link: the tail is treated as a path on the default branch.
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", null, "src/main"),
            RepoDigestTool.parseRepoUrl("github.com/owner/repo/src/main"),
        )
    }

    @Test
    fun `refuses inputs that name no repository`() {
        assertNull(RepoDigestTool.parseRepoUrl(""))
        assertNull(RepoDigestTool.parseRepoUrl("github.com"))
        assertNull(RepoDigestTool.parseRepoUrl("github.com/only-one-segment"))
        assertNull(RepoDigestTool.parseRepoUrl("   "))
    }

    @Test
    fun `a branch containing slashes is resolved against the known refs`() {
        val refs = setOf("main", "feature/x", "release/2.0")
        // Longest match wins, so `feature/x` is not read as branch `feature`.
        assertEquals(
            RepoDigestTool.RepoRef("o", "r", "feature/x", "src"),
            RepoDigestTool.parseRepoUrl("https://github.com/o/r/tree/feature/x/src", refs),
        )
        assertEquals(
            RepoDigestTool.RepoRef("o", "r", "release/2.0", null),
            RepoDigestTool.parseRepoUrl("https://github.com/o/r/tree/release/2.0", refs),
        )
        // Without the ref list the first segment is the branch, which the API call
        // corrects when it 404s.
        assertEquals(
            RepoDigestTool.RepoRef("o", "r", "feature", "x/src"),
            RepoDigestTool.parseRepoUrl("https://github.com/o/r/tree/feature/x/src"),
        )
    }

    // ── percent-escapes in a pasted URL ────────────────────────
    //
    // Reported: a blob URL copied straight out of the browser's address bar failed on
    // every file whose name contains a space — "…/blob/main/preseed/First%20Run" was
    // handed to the Contents API with the escape still in it and 404'd as "wrong
    // path". Only the URL is decoded; a `path` argument is already literal.

    @Test
    fun `a percent-escaped path in a pasted blob url is decoded before the api sees it`() {
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "preseed/First Run"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/preseed/First%20Run"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "preseed/Local State"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/preseed/Local%20State"),
        )
        // GitLab's `/-/` shape and an escaped ref both decode too.
        assertEquals(
            RepoDigestTool.RepoRef("group", "project", "main", "My Files/notes.md", "https://gitlab.com", RepoDigestTool.Provider.GITLAB),
            RepoDigestTool.parseRepoUrl("https://gitlab.com/group/project/-/blob/main/My%20Files/notes.md"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "release/x y", null),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/tree/release%2Fx%20y"),
        )
    }

    @Test
    fun `an escaped name that needs no decoding is left exactly as it is`() {
        // `chrome++.ini` exists in the wild: in a URL PATH a `+` is a literal plus, not
        // a space (that meaning belongs to a form-encoded query), so decoding must not
        // touch it.
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "chrome++.ini"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/chrome++.ini"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "a+b c.ini"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/a+b%20c.ini"),
        )
        // A literal percent is `%25`, and non-ASCII arrives as UTF-8 escapes.
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "100%done/café.ini"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/100%25done/caf%C3%A9.ini"),
        )
        // Nothing to decode -> byte-identical to the old behaviour.
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "src/main.kt"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/src/main.kt"),
        )
    }

    @Test
    fun `a malformed escape cannot break url parsing`() {
        // "%ZZ" and a trailing "%" are not decodable; URL parsing must still work and
        // keep the raw text rather than throw out of the tool.
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "weird%ZZname.ini"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/weird%ZZname.ini"),
        )
        assertEquals(
            RepoDigestTool.RepoRef("owner", "repo", "main", "half%.ini"),
            RepoDigestTool.parseRepoUrl("https://github.com/owner/repo/blob/main/half%.ini"),
        )
    }

    // ── multi-host shapes ───────────────────────────────────────────────────

    @Test
    fun `reads GitLab urls including the dash-tree form and self-hosted instances`() {
        val gitlab = RepoDigestTool.parseRepoUrl("https://gitlab.com/group/project/-/tree/main/src")
        assertEquals(RepoDigestTool.Provider.GITLAB, gitlab?.provider)
        assertEquals("group/project", gitlab?.slug)
        assertEquals("main", gitlab?.ref)
        assertEquals("src", gitlab?.path)
        assertEquals("https://gitlab.com", gitlab?.origin)

        // Self-hosted GitLab is recognised by the `/-/` path, not by the hostname.
        val selfHosted = RepoDigestTool.parseRepoUrl("https://git.company.example/team/app/-/blob/release/2.0/README.md")
        assertEquals(RepoDigestTool.Provider.GITLAB, selfHosted?.provider)
        assertEquals("https://git.company.example", selfHosted?.origin)
        assertEquals("release", selfHosted?.ref)
        assertEquals("2.0/README.md", selfHosted?.path)
    }

    @Test
    fun `reads Gitea family urls including the src branch form`() {
        val codeberg = RepoDigestTool.parseRepoUrl("https://codeberg.org/forgejo/forgejo/src/branch/main/routers")
        assertEquals(RepoDigestTool.Provider.GITEA, codeberg?.provider)
        assertEquals("main", codeberg?.ref)
        assertEquals("routers", codeberg?.path)

        val selfHosted = RepoDigestTool.parseRepoUrl("https://git.example.org/owner/repo/src/tag/v1.2.0/src")
        assertEquals(RepoDigestTool.Provider.GITEA, selfHosted?.provider)
        assertEquals("v1.2.0", selfHosted?.ref)
        assertEquals("src", selfHosted?.path)

        // A host nobody recognises is left UNKNOWN so it can be probed.
        val unknown = RepoDigestTool.parseRepoUrl("https://git.my-company.io/team/repo")
        assertEquals(RepoDigestTool.Provider.UNKNOWN, unknown?.provider)
        assertEquals("https://git.my-company.io", unknown?.origin)
    }

    @Test
    fun `reads Bitbucket urls`() {
        val bitbucket = RepoDigestTool.parseRepoUrl("https://bitbucket.org/workspace/repo/src/master/app/main.py")
        assertEquals(RepoDigestTool.Provider.BITBUCKET, bitbucket?.provider)
        assertEquals("master", bitbucket?.ref)
        assertEquals("app/main.py", bitbucket?.path)
        assertEquals("workspace/repo", bitbucket?.slug)
    }

    @Test
    fun `origin is kept so requests go back to the host the user named`() {
        val ref = RepoDigestTool.parseRepoUrl("https://git.example.com/owner/repo")
        assertEquals("https://git.example.com", ref?.origin)
        assertEquals(RepoDigestTool.Provider.UNKNOWN, ref?.provider)
        // Bare owner/repo stays GitHub, which is what the shape implies.
        assertEquals(RepoDigestTool.Provider.GITHUB, RepoDigestTool.parseRepoUrl("owner/repo")?.provider)
    }

    @Test
    fun `project paths are encoded for GitLab while file paths keep their slashes`() {
        assertEquals("group%2Fproject", RepoDigestTool.encodeProjectPath("group/project"))
        assertEquals("team%2Fsub%2Fapp", RepoDigestTool.encodeProjectPath("team/sub/app"))
        assertEquals("src/main/Main.kt", RepoDigestTool.encodePath("src/main/Main.kt"))
        assertEquals("dir/my%20file.kt", RepoDigestTool.encodePath("dir/my file.kt"))
    }

    @Test
    fun `blob urls are recognised so a single file can be read directly`() {
        assertTrue(RepoDigestTool.isBlobLike("https://github.com/o/r/blob/main/a.kt"))
        assertTrue(RepoDigestTool.isBlobLike("https://gitlab.com/g/p/-/blob/main/a.kt"))
        assertFalse(RepoDigestTool.isBlobLike("https://github.com/o/r/tree/main/src"))
    }

    // ── globs ───────────────────────────────────────────────────────────────

    @Test
    fun `globs respect segment boundaries`() {
        // `*` never crosses a slash: the reason `*.kt` is NOT enough for a repo.
        assertTrue(RepoDigestTool.globMatches("*.kt", "Main.kt"))
        assertFalse(RepoDigestTool.globMatches("*.kt", "src/Main.kt"))
        assertTrue(RepoDigestTool.globMatches("**/*.kt", "src/Main.kt"))
        assertTrue(RepoDigestTool.globMatches("src/**", "src/a/b/c.kt"))
        assertTrue(RepoDigestTool.globMatches("**/test/**", "app/src/test/x.kt"))
        // `**/` may match zero segments, so a/**/b also matches a/b.
        assertTrue(RepoDigestTool.globMatches("a/**/b", "a/b"))
        assertTrue(RepoDigestTool.globMatches("a?c", "abc"))
        assertFalse(RepoDigestTool.globMatches("a?c", "a/c"))
        // Regex metacharacters in a path are literals.
        assertTrue(RepoDigestTool.globMatches("a.b/c+d.kt", "a.b/c+d.kt"))
        assertFalse(RepoDigestTool.globMatches("a.b/c+d.kt", "axb/c+d.kt"))
    }

    @Test
    fun `include and exclude globs decide together`() {
        val include = RepoDigestTool.parseGlobs("**/*.kt, **/*.md")
        val exclude = RepoDigestTool.parseGlobs("**/test/**")
        assertTrue(RepoDigestTool.matchesGlobs("src/Main.kt", include, exclude))
        assertTrue(RepoDigestTool.matchesGlobs("README.md", include, exclude))
        assertFalse(RepoDigestTool.matchesGlobs("build.gradle.kts", include, exclude))
        assertFalse(RepoDigestTool.matchesGlobs("src/test/MainTest.kt", include, exclude))
        // No include list means everything not excluded.
        assertTrue(RepoDigestTool.matchesGlobs("anything.txt", emptyList(), exclude))
        assertEquals(listOf("a", "b"), RepoDigestTool.parseGlobs(" a ,, b,\n"))
    }

    // ── selection ───────────────────────────────────────────────────────────

    @Test
    fun `skips vendored, binary, minified and lock files`() {
        for (path in listOf(
            "node_modules/left-pad/index.js",
            "vendor/github.com/x/y.go",
            "dist/bundle.js",
            "app/build/outputs/apk.apk",
            "src/__pycache__/x.pyc",
            "Pods/Alamofire/Alamofire.swift",
            "assets/logo.png",
            "libs/native.so",
            "fonts/Inter.woff2",
            "package-lock.json",
            "yarn.lock",
            "go.sum",
            "public/app.min.js",
        )) {
            assertTrue("should skip: $path", RepoDigestTool.shouldSkip(path))
        }
        for (path in listOf(
            "src/main/kotlin/Main.kt",
            "README.md",
            "package.json",
            "build.gradle.kts",
            "docker-compose.yml",
            "docs/architecture.md",
        )) {
            assertFalse("should keep: $path", RepoDigestTool.shouldSkip(path))
        }
    }

    // ── rendering and caps ──────────────────────────────────────────────────

    @Test
    fun `tree rendering stays inside its budget and says when it cut`() {
        val entries = (1..40).map { RepoDigestTool.TreeEntry("src/file$it.kt", "blob", 100) }
        val text = RepoDigestTool.treeText(entries, budget = 200)
        assertTrue(text.length <= 200 + 80) // + the truncation line itself
        assertTrue(text, text.contains("[tree truncated: 40 entries total]"))
        val full = RepoDigestTool.treeText(entries, budget = 100_000)
        assertFalse(full.contains("truncated"))
        assertTrue(full, full.contains("src/file1.kt (100)"))
    }

    @Test
    fun `omission note names both reasons`() {
        assertEquals("", RepoDigestTool.buildOmissionNote(0, 0, 40))
        val note = RepoDigestTool.buildOmissionNote(5, 2, 40)
        assertTrue(note, note.contains("5 beyond the 40-file cap"))
        assertTrue(note, note.contains("2 larger than"))
        assertTrue(note, note.contains("narrow path/include"))
    }

    @Test
    fun `sanitize strips NULs and marks truncation`() {
        assertEquals("clean", RepoDigestTool.sanitize("clean", 100))
        assertEquals("a" + "b", RepoDigestTool.sanitize("a\u0000b", 100))
        val cut = RepoDigestTool.sanitize("x".repeat(50), 10)
        assertTrue(cut, cut.startsWith("x".repeat(10)))
        assertTrue(cut, cut.contains("file truncated at 10 chars"))
    }

    @Test
    fun `path encoding keeps slashes and escapes spaces`() {
        assertEquals("feature/x", RepoDigestTool.encodePath("feature/x"))
        assertEquals("dir/my%20file.kt", RepoDigestTool.encodePath("dir/my file.kt"))
    }

    @Test
    fun `definition tells the model to prefer this over cloning`() {
        val definition = RepoDigestTool.definition()
        assertEquals("repo_digest", definition.name)
        assertTrue(definition.required.contains("url"))
        assertTrue(definition.parameters.containsKey("include"))
        assertTrue(definition.description.contains("instead of cloning"))
        assertEquals(
            listOf("tree", "files", "file"),
            definition.parameters.getValue("format").enumValues,
        )
    }
}
