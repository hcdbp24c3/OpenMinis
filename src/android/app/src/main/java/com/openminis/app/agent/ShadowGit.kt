package com.openminis.app.agent

import android.content.Context
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * [T-coding-m3-shadow] A hidden git repository that remembers what the project
 * looked like before the agent's turn touched it.
 *
 * It is NOT the user's repository: `GIT_DIR` lives under [ROOT] in the sandbox,
 * `GIT_WORK_TREE` is the conversation's project directory, and nothing is ever
 * written into the project's own `.git`. The project's `.gitignore` is honoured,
 * so build output and dependencies are neither snapshotted nor reverted.
 *
 * The snapshot is taken lazily: once per user turn, immediately before the
 * first tool call that can change files. A turn that only reads costs nothing.
 * Unlike [com.openminis.app.tools.FileJournal] it also sees changes made by
 * shell commands.
 *
 * Every git call is best-effort with a short timeout: a project where the
 * snapshot fails or is slow simply has no "changes" card for that turn.
 */
object ShadowGit {
    const val ROOT = "/root/.cache/minis-shadow"
    const val REF = "refs/minis/turn"
    const val PREFS = "shell_approval"
    const val KEY_ENABLED = "shadow.git.enabled"

    const val SNAPSHOT_TIMEOUT_MS = 25_000L
    const val DIFF_TIMEOUT_MS = 25_000L
    const val PATCH_MAX_BYTES = 60_000

    const val MARK_SNAPSHOT = "MINIS_SNAPSHOT"
    const val MARK_NONE = "MINIS_NO_SNAPSHOT"
    const val MARK_NUMSTAT = "MINIS_NUMSTAT"
    const val MARK_PATCH = "MINIS_PATCH"
    const val MARK_REVERTED = "MINIS_REVERTED"

    private val MUTATING_TOOLS = setOf("file_write", "file_edit", "file_undo")

    /** One conversation's turn: whether a snapshot is still owed, and where the last one was taken. */
    data class Turn(val pending: Boolean, val dir: String? = null)

    data class FileChange(val path: String, val added: Int, val removed: Int, val binary: Boolean)

    data class Changes(val files: List<FileChange>, val patch: String, val truncated: Boolean) {
        val added: Int get() = files.sumOf { it.added }
        val removed: Int get() = files.sumOf { it.removed }
    }

    private val _turns = MutableStateFlow<Map<String, Turn>>(emptyMap())
    val turns: StateFlow<Map<String, Turn>> = _turns.asStateFlow()

    // ------------------------------------------------------------------ pure

    fun gitDir(sessionId: String): String =
        ROOT + "/" + sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    /** Environment and `cd` shared by every script. POSIX sh only (the sandbox shell is BusyBox ash). */
    fun preamble(sessionId: String, dir: String): String =
        "export GIT_DIR=" + SessionProject.shellQuote(gitDir(sessionId)) +
            " GIT_WORK_TREE=" + SessionProject.shellQuote(dir) + "\n" +
            "export GIT_AUTHOR_NAME=minis GIT_AUTHOR_EMAIL=minis@localhost " +
            "GIT_COMMITTER_NAME=minis GIT_COMMITTER_EMAIL=minis@localhost\n" +
            "export GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0='*' " +
            "GIT_CONFIG_KEY_1=core.quotepath GIT_CONFIG_VALUE_1=false\n" +
            "cd " + SessionProject.shellQuote(dir) + " || exit 97\n"

    fun snapshotScript(sessionId: String, dir: String): String =
        preamble(sessionId, dir) +
            "if [ ! -d \"\$GIT_DIR\" ]; then mkdir -p \"\$GIT_DIR\" && git init -q || exit 96; fi\n" +
            "git add -A . >/dev/null 2>&1\n" +
            "t=\$(git write-tree) || exit 95\n" +
            "c=\$(git commit-tree \"\$t\" -m snapshot) || exit 95\n" +
            "git update-ref $REF \"\$c\" || exit 95\n" +
            "echo \"$MARK_SNAPSHOT \$c\"\n"

    fun diffScript(sessionId: String, dir: String): String =
        preamble(sessionId, dir) +
            "git rev-parse -q --verify $REF >/dev/null 2>&1 || { echo $MARK_NONE; exit 0; }\n" +
            "git add -A . >/dev/null 2>&1\n" +
            "echo $MARK_NUMSTAT\n" +
            "git diff --cached --numstat $REF\n" +
            "echo $MARK_PATCH\n" +
            "git diff --cached --no-color $REF | head -c ${PATCH_MAX_BYTES + 1}\n"

    /**
     * Put the project back as the snapshot had it: files created since are
     * removed, everything else is rewritten from the snapshot. Ignored files
     * are never touched.
     */
    fun revertScript(sessionId: String, dir: String): String =
        preamble(sessionId, dir) +
            "git rev-parse -q --verify $REF >/dev/null 2>&1 || { echo $MARK_NONE; exit 0; }\n" +
            "git add -A . >/dev/null 2>&1\n" +
            "git diff --cached --name-only --diff-filter=A -z $REF | xargs -0 rm -f --\n" +
            "git read-tree $REF || exit 95\n" +
            "git checkout-index -a -f || exit 95\n" +
            "echo $MARK_REVERTED\n"

    /** Parses `git diff --numstat` lines: "added<TAB>removed<TAB>path", "-" for binary. */
    fun parseNumstat(text: String): List<FileChange> {
        val out = ArrayList<FileChange>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) continue
            val binary = parts[0] == "-" || parts[1] == "-"
            val added = parts[0].toIntOrNull() ?: 0
            val removed = parts[1].toIntOrNull() ?: 0
            if (!binary && parts[0].toIntOrNull() == null) continue
            out.add(FileChange(parts[2], added, removed, binary))
        }
        return out
    }

    /** Null when the output says there is no snapshot (or is not ours at all). */
    fun parseDiff(output: String): Changes? {
        val numAt = output.indexOf(MARK_NUMSTAT)
        val patchAt = output.indexOf(MARK_PATCH)
        if (numAt < 0 || patchAt < numAt) return null
        val numstat = output.substring(numAt + MARK_NUMSTAT.length, patchAt)
        var patch = output.substring(patchAt + MARK_PATCH.length).trimStart('\n', '\r')
        val truncated = patch.length > PATCH_MAX_BYTES
        if (truncated) patch = patch.take(PATCH_MAX_BYTES)
        return Changes(parseNumstat(numstat), patch, truncated)
    }

    /** One line for a card or a tool result: "3 files changed, +12 −4". */
    fun summary(changes: Changes): String {
        val n = changes.files.size
        if (n == 0) return "No changes since the turn started."
        return "$n file" + (if (n == 1) "" else "s") + " changed, +" + changes.added + " -" + changes.removed
    }

    /** Can this tool call change files? Read-only shell commands cannot. */
    fun isMutating(toolName: String, argsJson: String): Boolean {
        if (toolName in MUTATING_TOOLS) return true
        if (toolName != "shell_execute") return false
        val command = try {
            JSONObject(argsJson).optString("command", "")
        } catch (_: Exception) {
            ""
        }
        if (command.isBlank()) return false
        return CommandRisk.classify(command).level != CommandRisk.Level.READ_ONLY
    }

    /** The directory this call will work in: an explicit `cwd`, else the stored project. */
    fun targetDir(toolName: String, argsJson: String, storedProject: String?): String? {
        if (toolName == "shell_execute") {
            val cwd = try {
                val args = JSONObject(argsJson)
                if (args.has("cwd") && !args.isNull("cwd")) args.optString("cwd", "") else ""
            } catch (_: Exception) {
                ""
            }
            val requested = SessionProject.normalize(cwd)
            if (requested != null) {
                if (requested == SessionProject.DEFAULT_DIR) return null
                if (SessionProject.validationError(requested) != null) return null
                return requested
            }
        }
        return storedProject
    }

    // ----------------------------------------------------------------- state

    fun isEnabled(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    } catch (_: Exception) {
        true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (_: Exception) {
        }
    }

    /** The user sent a message: the next change in this conversation gets a fresh snapshot. */
    fun onUserTurn(sessionId: String) {
        if (sessionId.isEmpty()) return
        _turns.update { it + (sessionId to Turn(pending = true)) }
    }

    /** The directory of this conversation's current snapshot, or null when it has none. */
    fun snapshotDir(sessionId: String): String? {
        val turn = _turns.value[sessionId] ?: return null
        return if (turn.pending) null else turn.dir
    }

    /**
     * Called before every tool call. Takes the turn's snapshot if one is owed
     * and this call can change files. Never throws, never blocks the tool for
     * longer than [SNAPSHOT_TIMEOUT_MS].
     *
     * [shellSessionId] keys the shell process; [fsSessionId] is the conversation
     * whose project and files are in play (a sub agent's parent).
     */
    suspend fun beforeTool(
        context: Context,
        shellSessionId: String,
        fsSessionId: String,
        toolName: String,
        argsJson: String,
    ) {
        try {
            if (fsSessionId.isEmpty()) return
            val turn = _turns.value[fsSessionId]
            if (turn != null && !turn.pending) return
            if (!isMutating(toolName, argsJson)) return
            if (!isEnabled(context)) return
            val dir = targetDir(toolName, argsJson, SessionProject.get(context, fsSessionId)) ?: return
            // Settle the turn before running git, so a slow or failing snapshot
            // is attempted once per turn, not once per tool call.
            _turns.update { it + (fsSessionId to Turn(pending = false, dir = null)) }
            val result = ExecutionCoordinator.execute(
                sessionId = shellSessionId,
                command = snapshotScript(fsSessionId, dir),
                timeout = SNAPSHOT_TIMEOUT_MS,
                fsSessionId = fsSessionId,
            )
            if (result.exitCode == 0 && result.output.contains(MARK_SNAPSHOT)) {
                _turns.update { it + (fsSessionId to Turn(pending = false, dir = dir)) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** What changed since the turn's snapshot; null when there is no snapshot or git failed. */
    suspend fun changes(sessionId: String): Changes? {
        val dir = snapshotDir(sessionId) ?: return null
        return try {
            val result = ExecutionCoordinator.execute(
                sessionId = sessionId,
                command = diffScript(sessionId, dir),
                timeout = DIFF_TIMEOUT_MS,
                fsSessionId = sessionId,
            )
            if (result.exitCode != 0) null else parseDiff(result.output)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Put the project back as it was when the turn started. True on success. */
    suspend fun revert(sessionId: String): Boolean {
        val dir = snapshotDir(sessionId) ?: return false
        return try {
            val result = ExecutionCoordinator.execute(
                sessionId = sessionId,
                command = revertScript(sessionId, dir),
                timeout = DIFF_TIMEOUT_MS,
                fsSessionId = sessionId,
            )
            result.exitCode == 0 && result.output.contains(MARK_REVERTED)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
