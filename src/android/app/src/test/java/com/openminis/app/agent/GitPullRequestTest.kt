package com.openminis.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m4-pr] Where a pull request is sent and how the answer is read. */
class GitPullRequestTest {

    private val repo = GitPullRequest.Repo("github.com", "me/app")

    @Test
    fun `remotes of every shape give host and slug`() {
        assertEquals(repo, GitPullRequest.parseRemote("git@github.com:me/app.git"))
        assertEquals(repo, GitPullRequest.parseRemote("https://github.com/me/app.git"))
        assertEquals(repo, GitPullRequest.parseRemote("https://user:secret@GitHub.com/me/app"))
        assertEquals(repo, GitPullRequest.parseRemote("ssh://git@github.com:22/me/app.git"))
        assertEquals(
            GitPullRequest.Repo("gitlab.com", "group/sub/app"),
            GitPullRequest.parseRemote("https://gitlab.com/group/sub/app.git"),
        )
        assertNull(GitPullRequest.parseRemote(""))
        assertNull(GitPullRequest.parseRemote("/root/local/repo"))
        assertNull(GitPullRequest.parseRemote("https://github.com/onlyowner"))
    }

    @Test
    fun `only recognised hosts are handled in the app`() {
        assertEquals(GitPullRequest.Provider.GITHUB, GitPullRequest.providerFor("github.com"))
        assertEquals(GitPullRequest.Provider.GITLAB, GitPullRequest.providerFor("gitlab.corp.example"))
        assertEquals(GitPullRequest.Provider.GITEA, GitPullRequest.providerFor("codeberg.org"))
        assertNull(GitPullRequest.providerFor("bitbucket.org"))
        assertNull(GitPullRequest.providerFor("git.corp.example"))
    }

    @Test
    fun `the request goes to the remote's own host over https`() {
        val gh = GitPullRequest.buildCall(GitPullRequest.Provider.GITHUB, repo, " T ", "B", "feat/x", "main")
        assertEquals("https://api.github.com/repos/me/app/pulls", gh.url)
        assertEquals("Authorization", gh.authHeader)
        assertEquals("Bearer ", gh.authPrefix)
        val body = JSONObject(gh.body)
        assertEquals("T", body.getString("title"))
        assertEquals("feat/x", body.getString("head"))
        assertEquals("main", body.getString("base"))

        val gl = GitPullRequest.buildCall(
            GitPullRequest.Provider.GITLAB, GitPullRequest.Repo("gitlab.com", "group/sub/app"), "T", "", "dev", "main",
        )
        assertEquals("https://gitlab.com/api/v4/projects/group%2Fsub%2Fapp/merge_requests", gl.url)
        assertEquals("PRIVATE-TOKEN", gl.authHeader)
        assertEquals("dev", JSONObject(gl.body).getString("source_branch"))
        assertEquals("main", JSONObject(gl.body).getString("target_branch"))

        val gt = GitPullRequest.buildCall(
            GitPullRequest.Provider.GITEA, GitPullRequest.Repo("codeberg.org", "me/app"), "T", "", "dev", "main",
        )
        assertEquals("https://codeberg.org/api/v1/repos/me/app/pulls", gt.url)
        assertEquals("token ", gt.authPrefix)
    }

    @Test
    fun `missing pieces are caught before any request`() {
        assertNotNull(GitPullRequest.inputError("", "feat/x", "main"))
        assertNotNull(GitPullRequest.inputError("T", "", "main"))
        assertNotNull(GitPullRequest.inputError("T", "feat/x", " "))
        assertNotNull(GitPullRequest.inputError("T", "main", "main"))
        assertNull(GitPullRequest.inputError("T", "feat/x", "main"))
    }

    @Test
    fun `answers are turned into a link or a readable error`() {
        val ok = GitPullRequest.parseResponse(201, "{\"html_url\":\"https://github.com/me/app/pull/7\"}")
        assertTrue(ok.ok)
        assertEquals("https://github.com/me/app/pull/7", ok.url)
        val mr = GitPullRequest.parseResponse(201, "{\"web_url\":\"https://gitlab.com/g/a/-/merge_requests/3\"}")
        assertEquals("https://gitlab.com/g/a/-/merge_requests/3", mr.url)

        val dup = GitPullRequest.parseResponse(
            422, "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"A pull request already exists\"}]}",
        )
        assertFalse(dup.ok)
        assertNull(dup.url)
        assertTrue(dup.message.contains("422"))
        assertTrue(dup.message.contains("A pull request already exists"))
        assertTrue(GitPullRequest.parseResponse(401, "{\"message\":\"Bad credentials\"}").message.contains("Git Vault"))
        assertTrue(GitPullRequest.parseResponse(409, "{\"message\":[\"Another open merge request already exists\"]}").message.contains("Another open"))
        assertFalse(GitPullRequest.parseResponse(502, "<html>bad gateway</html>").ok)
    }
}
