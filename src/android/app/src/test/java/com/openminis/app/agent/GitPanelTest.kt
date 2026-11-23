package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m4-git] The git sheet's commands and how their output is read. */
class GitPanelTest {

    @Test
    fun `status output is split into branch, remote, files and diff`() {
        val out = GitPanel.MARK_BRANCH + "\nfeat/x\n" + GitPanel.MARK_REMOTE + "\ngit@github.com:me/app.git\n" +
            GitPanel.MARK_BASE + "\norigin/main\n" + GitPanel.MARK_BRANCHES + "\nfeat/x\nmain\n" +
            GitPanel.MARK_STATUS + "\n M src/a.kt\n?? \"new file.txt\"\nA  b.kt\nR  old.kt -> moved.kt\n" + GitPanel.MARK_DIFF +
            "\ndiff --git a/src/a.kt b/src/a.kt\n+x\n"
        val s = GitPanel.parseStatus(out)
        assertTrue(s.isRepo)
        assertEquals("feat/x", s.branch)
        assertEquals("git@github.com:me/app.git", s.remote)
        assertEquals("main", s.base)
        assertEquals(listOf("feat/x", "main"), s.branches)
        assertTrue(s.anyStaged)
        assertEquals(listOf(false, false, true, true), s.files.map { it.staged })
        assertEquals(
            listOf(
                GitPanel.FileStatus(" M", "src/a.kt"),
                GitPanel.FileStatus("??", "new file.txt"),
                GitPanel.FileStatus("A ", "b.kt"),
                GitPanel.FileStatus("R ", "moved.kt"),
            ),
            s.files,
        )
        assertTrue(s.diff.startsWith("diff --git"))
        assertFalse(s.diffTruncated)
    }

    @Test
    fun `a directory that is not a repository is reported as such`() {
        assertFalse(GitPanel.parseStatus(GitPanel.MARK_NOT_REPO + "\n").isRepo)
        assertFalse(GitPanel.parseStatus("").isRepo)
    }

    @Test
    fun `a clean repository with no remote parses to empty fields`() {
        val out = GitPanel.MARK_BRANCH + "\nmain\n" + GitPanel.MARK_REMOTE + "\n" + GitPanel.MARK_BASE + "\n" + GitPanel.MARK_BRANCHES + "\nmain\n" +
            GitPanel.MARK_STATUS + "\n" + GitPanel.MARK_DIFF + "\n"
        val s = GitPanel.parseStatus(out)
        assertTrue(s.isRepo)
        assertEquals("main", s.branch)
        assertEquals("", s.remote)
        assertEquals("", s.base)
        assertFalse(s.anyStaged)
        assertTrue(s.files.isEmpty())
        assertEquals("", s.diff)
    }

    @Test
    fun `the commit message is quoted, and an empty one is refused`() {
        assertNull(GitPanel.commitScript("/root/p", "   "))
        assertNull(GitPanel.commitScript("/root/p", "x".repeat(GitPanel.MESSAGE_MAX_CHARS + 1)))
        val script = GitPanel.commitScript("/root/my app", "fix: it's \$(rm -rf /) done")
        assertNotNull(script)
        val s = script.orEmpty()
        assertTrue(s.contains("cd '/root/my app' || exit 97"))
        assertTrue(s.contains("git commit -m 'fix: it'\\''s \$(rm -rf /) done'"))
        assertTrue(s.contains("git add -A"))
        val staged = GitPanel.commitScript("/root/p", "msg", all = false).orEmpty()
        assertFalse("committing what is staged must not stage the rest", staged.contains("git add"))
    }

    @Test
    fun `one file is staged or unstaged, quoted, without touching the work tree`() {
        val stage = GitPanel.stageScript("/root/p", "dir with space/it's.kt").orEmpty()
        assertTrue(stage.contains("git add -A -- 'dir with space/it'\\''s.kt'"))
        val unstage = GitPanel.unstageScript("/root/p", "a.kt").orEmpty()
        assertTrue(unstage.contains("git reset -q -- 'a.kt'"))
        assertFalse(unstage.contains("--hard"))
        assertFalse(unstage.contains("checkout"))
        assertNull(GitPanel.stageScript("/root/p", "a\nb"))
        assertNull(GitPanel.unstageScript("/root/p", ""))
    }

    @Test
    fun `quoted paths from git are read back`() {
        assertEquals("new file.txt", GitPanel.unquotePath("\"new file.txt\""))
        assertEquals("a\"b\\c", GitPanel.unquotePath("\"a\\\"b\\\\c\""))
        assertEquals("plain.kt", GitPanel.unquotePath("plain.kt"))
    }

    @Test
    fun `branch names are checked before they reach the shell`() {
        for (good in listOf("main", "feat/coding-m4", "v1.2", "fix_123")) assertNull(good, GitPanel.branchNameError(good))
        for (bad in listOf("", "-x", "a b", "a..b", "a~1", "x:y", "a/", "/a", "a.lock", "a\\b", "a*", "x@{1}")) {
            assertNotNull(bad, GitPanel.branchNameError(bad))
        }
        assertTrue(GitPanel.switchScript("/root/p", "feat/x").orEmpty().contains("git switch 'feat/x'"))
        assertTrue(GitPanel.createBranchScript("/root/p", "feat/x").orEmpty().contains("git switch -c 'feat/x'"))
        assertNull(GitPanel.switchScript("/root/p", "-f"))
        assertNull(GitPanel.createBranchScript("/root/p", "a b"))
    }

    @Test
    fun `network commands never wait for a password prompt`() {
        for (script in listOf(GitPanel.pullScript("/root/p"), GitPanel.pushScript("/root/p"))) {
            assertTrue(script.contains("GIT_TERMINAL_PROMPT=0"))
        }
        assertTrue(GitPanel.pullScript("/root/p").contains("git pull --ff-only"))
        assertTrue(GitPanel.pushScript("/root/p").contains("git push -u origin HEAD"))
        assertFalse("the sheet never force-pushes", GitPanel.pushScript("/root/p").contains("--force"))
    }

    @Test
    fun `pull request links are built for known hosts only`() {
        assertEquals(
            "https://github.com/me/app/compare/feat/x?expand=1",
            GitPanel.newPullRequestUrl("git@github.com:me/app.git", "feat/x"),
        )
        assertEquals(
            "https://github.com/me/app/compare/fix%20it?expand=1",
            GitPanel.newPullRequestUrl("https://token@github.com/me/app.git", "fix it"),
        )
        assertEquals(
            "https://gitlab.com/group/sub/app/-/merge_requests/new?merge_request%5Bsource_branch%5D=dev",
            GitPanel.newPullRequestUrl("https://gitlab.com/group/sub/app.git", "dev"),
        )
        assertEquals(
            "https://codeberg.org/me/app/compare/dev",
            GitPanel.newPullRequestUrl("ssh://git@codeberg.org/me/app.git", "dev"),
        )
        assertNull(GitPanel.newPullRequestUrl("git@git.corp.example:team/app.git", "dev"))
        assertNull(GitPanel.newPullRequestUrl("", "dev"))
        assertNull(GitPanel.newPullRequestUrl("git@github.com:me/app.git", ""))
    }
}
