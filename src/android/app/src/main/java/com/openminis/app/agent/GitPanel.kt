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
    const val MARK_BASE = "MINIS_BASE"
    const val MARK_BRANCHES = "MINIS_BRANCHES"
    const val MARK_STATUS = "MINIS_STATUS"
    const val MARK_DIFF = "MINIS_DIFF"

    const val DIFF_MAX_BYTES = 60_000
    const val QUICK_TIMEOUT_MS = 30_000L
    const val NETWORK_TIMEOUT_MS = 180_000L
    const val MESSAGE_MAX_CHARS = 2_000
    const val BRANCH_MAX_CHARS = 200
    const val MAX_BRANCHES = 200

    /** [code] is git's two-letter porcelain status: index column, then work tree. */
    data class FileStatus(val code: String, val path: String) {
        /** Something of this file is in the index, waiting to be committed. */
        val staged: Boolean get() = code.isNotEmpty() && code[0] != ' ' && code[0] != '?'
    }

    data class Status(
        val isRepo: Boolean,
        val branch: String = "",
        val remote: String = "",
        /** The remote's default branch (from origin/HEAD), or "" when unknown. */
        val base: String = "",
        val branches: List<String> = emptyList(),
        val files: List<FileStatus> = emptyList(),
        val diff: String = "",
        val diffTruncated: Boolean = false,
    ) {
        val anyStaged: Boolean get() = files.any { it.staged }
    }

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
            "echo $MARK_BASE\n" +
            "git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null\n" +
            "echo $MARK_BRANCHES\n" +
            "git branch --format='%(refname:short)' 2>/dev/null | head -n $MAX_BRANCHES\n" +
            "echo $MARK_STATUS\n" +
            "git -c core.quotepath=false status --porcelain 2>&1\n" +
            "echo $MARK_DIFF\n" +
            "{ git diff --no-color HEAD 2>/dev/null || git diff --no-color 2>/dev/null; } | head -c ${DIFF_MAX_BYTES + 1}\n"

    /**
     * Commit. With [all] everything is staged first; without it only what the
     * user staged is committed. Null when the message is unusable.
     */
    fun commitScript(dir: String, message: String, all: Boolean = true): String? {
        val msg = message.trim()
        if (msg.isEmpty() || msg.length > MESSAGE_MAX_CHARS || msg.indexOf('\u0000') >= 0) return null
        val add = if (all) "git add -A 2>&1 && " else ""
        return head(dir) + add + "git commit -m " + q(msg) + " 2>&1\n"
    }

    private fun pathOk(path: String): Boolean =
        path.isNotEmpty() && path.indexOf('\n') < 0 && path.indexOf('\r') < 0 && path.indexOf('\u0000') < 0

    /** Put one file in the index. `add -A` so a deleted file can be staged too. */
    fun stageScript(dir: String, path: String): String? {
        if (!pathOk(path)) return null
        return head(dir) + "git add -A -- " + q(path) + " 2>&1\n"
    }

    /** Take one file back out of the index; the work tree is not touched. */
    fun unstageScript(dir: String, path: String): String? {
        if (!pathOk(path)) return null
        val p = q(path)
        return head(dir) +
            "if git rev-parse -q --verify HEAD >/dev/null 2>&1; then git reset -q -- $p 2>&1; " +
            "else git rm -q --cached -r -- $p 2>&1; fi\n"
    }

    /** Null when [name] can be used as a branch name, else what is wrong with it. */
    fun branchNameError(name: String): String? {
        if (name.isEmpty()) return "Type a branch name."
        if (name.length > BRANCH_MAX_CHARS) return "That branch name is too long."
        if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".") ||
            name.endsWith(".lock") || name.contains("..") || name.contains("//") || name.contains("@{")
        ) {
            return "That is not a valid branch name."
        }
        for (c in name) {
            if (c <= ' ' || c == '\u007f' || "~^:?*[\\".indexOf(c) >= 0) return "That is not a valid branch name."
        }
        return null
    }

    fun switchScript(dir: String, branch: String): String? {
        if (branchNameError(branch) != null) return null
        return head(dir) + "git switch " + q(branch) + " 2>&1\n"
    }

    fun createBranchScript(dir: String, branch: String): String? {
        if (branchNameError(branch) != null) return null
        return head(dir) + "git switch -c " + q(branch) + " 2>&1\n"
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

    /** Git wraps a path in quotes when it has spaces or escapes; undo that. */
    fun unquotePath(raw: String): String {
        if (raw.length < 2 || !raw.startsWith("\"") || !raw.endsWith("\"")) return raw
        val inner = raw.substring(1, raw.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                val n = inner[i + 1]
                sb.append(if (n == 't') '\t' else n)
                i += 2
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    fun parseStatus(output: String): Status {
        if (output.contains(MARK_NOT_REPO) || !output.contains(MARK_STATUS)) return Status(isRepo = false)
        val files = ArrayList<FileStatus>()
        for (line in section(output, MARK_STATUS, MARK_DIFF).lines()) {
            if (line.length < 4) continue
            var path = line.substring(3)
            // A rename is "old -> new"; the new name is the one that exists.
            val arrow = path.indexOf(" -> ")
            if (arrow >= 0 && (line[0] == 'R' || line[0] == 'C')) path = path.substring(arrow + 4)
            files.add(FileStatus(line.substring(0, 2), unquotePath(path)))
        }
        var diff = section(output, MARK_DIFF, null)
        val truncated = diff.length > DIFF_MAX_BYTES
        if (truncated) diff = diff.take(DIFF_MAX_BYTES)
        return Status(
            isRepo = true,
            branch = section(output, MARK_BRANCH, MARK_REMOTE).trim(),
            remote = section(output, MARK_REMOTE, MARK_BASE).trim(),
            base = section(output, MARK_BASE, MARK_BRANCHES).trim().removePrefix("origin/"),
            branches = section(output, MARK_BRANCHES, MARK_STATUS).lines().map { it.trim() }.filter { it.isNotEmpty() },
            files = files,
            diff = diff,
            diffTruncated = truncated,
        )
    }

    /**
     * The web page that opens a pull/merge request for [branch], for the hosts
     * whose URL scheme is known. Null for anything else — a wrong link is worse
     * than none. Used when the request cannot be created from the app.
     */
    fun newPullRequestUrl(remote: String, branch: String): String? {
        if (branch.isBlank()) return null
        val repo = GitPullRequest.parseRemote(remote) ?: return null
        val b = java.net.URLEncoder.encode(branch, "UTF-8").replace("+", "%20").replace("%2F", "/")
        val slug = repo.slug
        return when (repo.host) {
            "github.com" -> "https://github.com/$slug/compare/$b?expand=1"
            "gitlab.com" -> "https://gitlab.com/$slug/-/merge_requests/new?merge_request%5Bsource_branch%5D=$b"
            "bitbucket.org" -> "https://bitbucket.org/$slug/pull-requests/new?source=$b"
            "codeberg.org" -> "https://codeberg.org/$slug/compare/$b"
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

    private suspend fun runOr(sessionId: String, script: String?, timeout: Long, problem: String): Outcome =
        if (script == null) Outcome(false, problem) else run(sessionId, script, timeout)

    suspend fun status(sessionId: String, dir: String): Status? {
        val out = run(sessionId, statusScript(dir), QUICK_TIMEOUT_MS)
        return if (out.ok) parseStatus(out.output) else null
    }

    suspend fun commit(sessionId: String, dir: String, message: String, all: Boolean): Outcome =
        runOr(sessionId, commitScript(dir, message, all), QUICK_TIMEOUT_MS, "Write a commit message first.")

    suspend fun setStaged(sessionId: String, dir: String, path: String, staged: Boolean): Outcome =
        runOr(
            sessionId,
            if (staged) stageScript(dir, path) else unstageScript(dir, path),
            QUICK_TIMEOUT_MS,
            "That file name cannot be passed to git.",
        )

    suspend fun switchTo(sessionId: String, dir: String, branch: String, create: Boolean): Outcome =
        runOr(
            sessionId,
            if (create) createBranchScript(dir, branch) else switchScript(dir, branch),
            QUICK_TIMEOUT_MS,
            branchNameError(branch) ?: "That is not a valid branch name.",
        )

    suspend fun pull(sessionId: String, dir: String): Outcome = run(sessionId, pullScript(dir), NETWORK_TIMEOUT_MS)

    suspend fun push(sessionId: String, dir: String): Outcome = run(sessionId, pushScript(dir), NETWORK_TIMEOUT_MS)
}
