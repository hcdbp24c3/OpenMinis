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
            GitPanel.MARK_STATUS + "\n M src/a.kt\n?? new file.txt\nA  b.kt\n" + GitPanel.MARK_DIFF +
            "\ndiff --git a/src/a.kt b/src/a.kt\n+x\n"
        val s = GitPanel.parseStatus(out)
        assertTrue(s.isRepo)
        assertEquals("feat/x", s.branch)
        assertEquals("git@github.com:me/app.git", s.remote)
        assertEquals(
            listOf(
                GitPanel.FileStatus(" M", "src/a.kt"),
                GitPanel.FileStatus("??", "new file.txt"),
                GitPanel.FileStatus("A ", "b.kt"),
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
        val out = GitPanel.MARK_BRANCH + "\nmain\n" + GitPanel.MARK_REMOTE + "\n" + GitPanel.MARK_STATUS + "\n" + GitPanel.MARK_DIFF + "\n"
        val s = GitPanel.parseStatus(out)
        assertTrue(s.isRepo)
        assertEquals("main", s.branch)
        assertEquals("", s.remote)
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
