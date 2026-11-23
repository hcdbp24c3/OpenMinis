package com.openminis.app.agent

import com.openminis.app.sandbox.ExecutionCoordinator

/**
 * [T-coding-m4-git] The logic behind the git sheet: the commands it runs in the
 * project directory, and how their output is read. Unlike [ShadowGit] this is
 * the user's own repository, and every action here is one the user tapped.
 *
 * Credentials are not handled here: the Git Vault already projects tokens and
 * SSH keys into the sandbox, so plain `git pull` / `git push` authenticate.
 */
object GitPanel {
    const val MARK_NOT_REPO = "MINIS_NOT_A_REPO"
    const val MARK_BRANCH = "MINIS_BRANCH"
    const val MARK_REMOTE = "MINIS_REMOTE"
    const val MARK_STATUS = "MINIS_STATUS"
    const val MARK_DIFF = "MINIS_DIFF"

    const val DIFF_MAX_BYTES = 60_000
    const val QUICK_TIMEOUT_MS = 30_000L
    const val NETWORK_TIMEOUT_MS = 180_000L
    const val MESSAGE_MAX_CHARS = 2_000

    data class FileStatus(val code: String, val path: String)

    data class Status(
        val isRepo: Boolean,
        val branch: String = "",
        val remote: String = "",
        val files: List<FileStatus> = emptyList(),
        val diff: String = "",
        val diffTruncated: Boolean = false,
    )

    data class Outcome(val ok: Boolean, val output: String)

    // ------------------------------------------------------------------ pure

    private fun q(s: String): String = SessionProject.shellQuote(s)

    private fun head(dir: String): String =
        "export GIT_TERMINAL_PROMPT=0 GIT_PAGER=cat\ncd " + q(dir) + " || exit 97\n"

    fun statusScript(dir: String): String =
        head(dir) +
            "git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo $MARK_NOT_REPO; exit 0; }\n" +
            "echo $MARK_BRANCH\n" +
            "git branch --show-current 2>/dev/null\n" +
            "echo $MARK_REMOTE\n" +
            "git remote get-url origin 2>/dev/null\n" +
            "echo $MARK_STATUS\n" +
            "git -c core.quotepath=false status --porcelain 2>&1\n" +
            "echo $MARK_DIFF\n" +
            "{ git diff --no-color HEAD 2>/dev/null || git diff --no-color 2>/dev/null; } | head -c ${DIFF_MAX_BYTES + 1}\n"

    /** Stage everything and commit. Null when the message is unusable. */
    fun commitScript(dir: String, message: String): String? {
        val msg = message.trim()
        if (msg.isEmpty() || msg.length > MESSAGE_MAX_CHARS || msg.indexOf('\u0000') >= 0) return null
        return head(dir) + "git add -A 2>&1 && git commit -m " + q(msg) + " 2>&1\n"
    }

    fun pullScript(dir: String): String = head(dir) + "git pull --ff-only 2>&1\n"

    fun pushScript(dir: String): String = head(dir) + "git push -u origin HEAD 2>&1\n"

    private fun section(output: String, start: String, end: String?): String {
        val a = output.indexOf(start)
        if (a < 0) return ""
        val from = a + start.length
        val b = if (end == null) -1 else output.indexOf(end, from)
        return (if (b < 0) output.substring(from) else output.substring(from, b)).trim('\n', '\r')
    }

    fun parseStatus(output: String): Status {
        if (output.contains(MARK_NOT_REPO) || !output.contains(MARK_STATUS)) return Status(isRepo = false)
        val files = ArrayList<FileStatus>()
        for (line in section(output, MARK_STATUS, MARK_DIFF).lines()) {
            if (line.length < 4) continue
            files.add(FileStatus(line.substring(0, 2), line.substring(3)))
        }
        var diff = section(output, MARK_DIFF, null)
        val truncated = diff.length > DIFF_MAX_BYTES
        if (truncated) diff = diff.take(DIFF_MAX_BYTES)
        return Status(
            isRepo = true,
            branch = section(output, MARK_BRANCH, MARK_REMOTE).trim(),
            remote = section(output, MARK_REMOTE, MARK_STATUS).trim(),
            files = files,
            diff = diff,
            diffTruncated = truncated,
        )
    }

    /**
     * The web page that opens a pull/merge request for [branch], for the hosts
     * whose URL scheme is known. Null for anything else — a wrong link is worse
     * than none. The request itself is created by the user in their browser, so
     * no token leaves the vault for it.
     */
    fun newPullRequestUrl(remote: String, branch: String): String? {
        if (branch.isBlank()) return null
        var r = remote.trim()
        if (r.isEmpty()) return null
        val host: String
        val path: String
        if (r.startsWith("git@")) {
            val colon = r.indexOf(':')
            if (colon < 0) return null
            host = r.substring(4, colon)
            path = r.substring(colon + 1)
        } else {
            r = r.removePrefix("ssh://").removePrefix("https://").removePrefix("http://")
            val at = r.indexOf('@')
            val slashFirst = r.indexOf('/')
            if (at in 0 until (if (slashFirst < 0) r.length else slashFirst)) r = r.substring(at + 1)
            val slash = r.indexOf('/')
            if (slash < 0) return null
            host = r.substring(0, slash).substringBefore(':')
            path = r.substring(slash + 1)
        }
        val repo = path.removeSuffix("/").removeSuffix(".git")
        if (repo.count { it == '/' } < 1 || repo.contains(' ')) return null
        val b = java.net.URLEncoder.encode(branch, "UTF-8").replace("+", "%20").replace("%2F", "/")
        return when (host.lowercase()) {
            "github.com" -> "https://github.com/$repo/compare/$b?expand=1"
            "gitlab.com" -> "https://gitlab.com/$repo/-/merge_requests/new?merge_request%5Bsource_branch%5D=$b"
            "bitbucket.org" -> "https://bitbucket.org/$repo/pull-requests/new?source=$b"
            "codeberg.org" -> "https://codeberg.org/$repo/compare/$b"
            else -> null
        }
    }

    // ------------------------------------------------------------------- run

    private suspend fun run(sessionId: String, script: String, timeout: Long): Outcome = try {
        val result = ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = script,
            timeout = timeout,
            fsSessionId = sessionId,
        )
        Outcome(result.exitCode == 0, result.output.trim())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(false, e.message ?: "The command could not be run.")
    }

    suspend fun status(sessionId: String, dir: String): Status? {
        val out = run(sessionId, statusScript(dir), QUICK_TIMEOUT_MS)
        return if (out.ok) parseStatus(out.output) else null
    }

    suspend fun commit(sessionId: String, dir: String, message: String): Outcome {
        val script = commitScript(dir, message) ?: return Outcome(false, "Write a commit message first.")
        return run(sessionId, script, QUICK_TIMEOUT_MS)
    }

    suspend fun pull(sessionId: String, dir: String): Outcome = run(sessionId, pullScript(dir), NETWORK_TIMEOUT_MS)

    suspend fun push(sessionId: String, dir: String): Outcome = run(sessionId, pushScript(dir), NETWORK_TIMEOUT_MS)
}
