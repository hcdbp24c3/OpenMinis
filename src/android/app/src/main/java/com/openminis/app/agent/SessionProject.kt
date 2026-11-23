package com.openminis.app.agent

import android.content.Context
import com.openminis.app.sandbox.PRootKernel
import java.io.File

/**
 * [T-coding-m1-project] A conversation's PROJECT DIRECTORY: where `shell_execute`
 * starts by default, and whose `AGENTS.md` is loaded into the system prompt.
 *
 * Why this exists: every `shell_execute` is a fresh `/bin/sh -c` started in
 * `/root` (see [com.openminis.app.sandbox.ShellExecutionStrategy] for why the
 * warm shell is not coming back), so a model working inside a repository had to
 * prefix `cd <repo> &&` on every single command, and a repository's own
 * instructions file was only ever read if the model thought to look for it.
 *
 * The project is set by the model, not by a picker: passing `cwd` to
 * `shell_execute` makes that directory the conversation's project, and later
 * calls default to it. That keeps the whole feature inside one tool parameter —
 * no new tool to render, no new screen — and it is what the model would have
 * typed anyway.
 *
 * Storage is one string per session in plain prefs. It is keyed by the FS
 * session id, which for a sub agent is its PARENT's id, so a sub agent starts in
 * the parent's project without being told. A sub agent never WRITES the key
 * (see [resolveForShell]'s `persist`): a scout that wanders into another
 * directory must not move the conversation that delegated to it.
 *
 * Everything that does not need a [Context] is a pure function, so the rules
 * (what a valid directory is, how the command is wrapped, how the prompt
 * fragment is assembled and truncated) are unit-testable on the JVM.
 */
object SessionProject {

    private const val PREFS = "session_project"

    /** Where a fresh shell starts (`proot -w /root`). Naming it as `cwd` clears the project. */
    const val DEFAULT_DIR = "/root"

    /**
     * Exit status of the injected `cd` when the directory is gone. Chosen to be
     * distinguishable from a command's own failure (1/2), from "not found" (127)
     * and from a timeout (124).
     */
    const val CD_FAILED_EXIT = 97

    /** Longest accepted directory path. A bound, not a filesystem limit. */
    const val DIR_MAX_LENGTH = 512

    /**
     * Cap on the instructions file inlined into the system prompt. It is paid on
     * EVERY request of the conversation, so it is deliberately small; the notice
     * appended on truncation tells the model how to read the rest.
     */
    const val INSTRUCTIONS_MAX_CHARS = 8_000

    /** Looked up in this order at the project root; the first readable one wins. */
    val INSTRUCTION_FILES: List<String> = listOf("AGENTS.md", "CLAUDE.md")

    // ------------------------------------------------------------------ pure

    /**
     * Trim, and drop trailing slashes (`/a/b/` → `/a/b`; `/` stays `/`).
     * Returns null for a blank value and for the literal string "null", which is
     * what `JSONObject.optString` yields for an explicit JSON null.
     */
    fun normalize(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == "null") return null
        if (t.all { it == '/' }) return "/"
        return t.trimEnd('/')
    }

    /**
     * Why [dir] cannot be a working directory, or null when it can.
     *
     * The value is spliced into a shell command, so anything that could end the
     * quoted argument or the line is refused outright rather than escaped: a
     * directory name containing a newline is far likelier to be a mangled tool
     * call than a real path.
     */
    fun validationError(dir: String): String? = when {
        !dir.startsWith("/") ->
            "Error: 'cwd' must be an absolute Linux path (e.g. /var/minis/workspace/myrepo), got: $dir"
        dir.length > DIR_MAX_LENGTH ->
            "Error: 'cwd' is too long (${dir.length} chars, maximum $DIR_MAX_LENGTH)"
        dir.any { it == '\n' || it == '\r' || it == '\u0000' } ->
            "Error: 'cwd' must be a single-line path"
        dir.split('/').any { it == ".." } ->
            "Error: 'cwd' must not contain '..' segments; pass the resolved absolute path"
        else -> null
    }

    /** POSIX single-quote [s] for `/bin/sh`: `it's` → `'it'\''s'`. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * [command] prefixed so it runs in [dir].
     *
     * A separate first line, not `cd … && (…)`: the command may itself be
     * multi-line, and wrapping it in a subshell would change what `exit`,
     * `set -e` and background jobs mean inside it. When the directory is gone
     * the shell prints its own `cd: can't cd to …` (stderr is merged into the
     * tool output) and exits [CD_FAILED_EXIT], so the command never runs in the
     * wrong place.
     */
    fun wrapCommand(dir: String, command: String): String =
        "cd ${shellQuote(dir)} || exit $CD_FAILED_EXIT\n$command"

    /** [text] cut to [max] characters with a notice naming [path], or unchanged when it fits. */
    fun truncateInstructions(text: String, path: String, max: Int = INSTRUCTIONS_MAX_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.length <= max) return trimmed
        return trimmed.take(max).trimEnd() +
            "\n[… truncated at $max of ${trimmed.length} characters — read the rest with file_read on $path]"
    }

    /**
     * The block appended to the system prompt's "Runtime context" when the
     * conversation has a project.
     *
     * It lives in the runtime suffix, never in the static base: the base is kept
     * byte-stable so provider prompt caching (prefix-based) keeps hitting, and a
     * project is exactly the kind of per-conversation fact that would break it.
     *
     * [instructions] is the already-truncated body of [instructionsFile] (a file
     * name such as `AGENTS.md`), or null when the project has none.
     */
    fun promptFragment(dir: String, instructionsFile: String?, instructions: String?): String = buildString {
        append("- Project directory: ").append(dir)
        append(" — shell_execute starts here by default. Pass `cwd` to run one command elsewhere or to switch project; `cwd: \"")
        append(DEFAULT_DIR).append("\"` clears it.\n\n")
        append(CODING_GUIDANCE)
        if (instructionsFile != null && !instructions.isNullOrBlank()) {
            append("\n\nProject instructions — the contents of ").append(dir.trimEnd('/')).append('/').append(instructionsFile)
            append(", written by the project's maintainers. Follow them for work inside this project; they do not override the user's requests in this conversation:\n")
            append("<project_instructions>\n").append(instructions).append("\n</project_instructions>")
        }
    }

    /**
     * How to behave inside a project. Short on purpose: it rides on every request
     * of a project conversation.
     */
    const val CODING_GUIDANCE: String =
        "Working in this project:\n" +
            "- Look before you change: find the relevant code with `rg` and file_read, and match the conventions already in the files you touch.\n" +
            "- Make targeted edits with file_edit; do not rewrite a whole file to change a few lines.\n" +
            "- After a change, run the project's own checks (tests, lint, build) when they exist and are affordable here, and say what you ran and what it returned. If you could not run them, say so plainly instead of implying they passed.\n" +
            "- Version control: do not commit, push, reset, rebase, stash or discard changes unless the user asked for it.\n" +
            "- When you finish, list the files you changed."

    // --------------------------------------------------------------- storage

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The session's project directory, or null when it has none. */
    fun get(context: Context, sessionId: String): String? =
        normalize(prefs(context).getString(sessionId, null))?.takeIf { it != DEFAULT_DIR }

    fun set(context: Context, sessionId: String, dir: String) {
        prefs(context).edit().putString(sessionId, dir).apply()
        _version.value = _version.value + 1
    }

    fun clear(context: Context, sessionId: String) {
        prefs(context).edit().remove(sessionId).apply()
        _version.value = _version.value + 1
    }

    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** [T-coding-m4-git] Bumped whenever a project is set or cleared, so the UI can re-read [get]. */
    val version: kotlinx.coroutines.flow.StateFlow<Int> get() = _version

    // ----------------------------------------------------------------- shell

    /**
     * What one `shell_execute` call should do about its working directory.
     *
     * @property dir the directory to `cd` into, or null to leave the shell where
     *   it starts ([DEFAULT_DIR]).
     * @property error a ready tool-result message when `cwd` is unusable; the
     *   command must not run.
     */
    data class Resolution(val dir: String?, val error: String? = null)

    /**
     * Decide the working directory for a call whose `cwd` argument is [cwdArg].
     *
     *  - no `cwd` → the session's project, if it has one;
     *  - `cwd` = [DEFAULT_DIR] → run in the default place, and forget the project;
     *  - any other `cwd` → run there, and remember it as the project.
     *
     * [persist] is false for a sub agent, which may use a `cwd` but never moves
     * its parent's project.
     *
     * A directory is only REMEMBERED when it resolves to an existing host
     * directory. An unresolvable one still runs (the injected `cd` reports the
     * failure itself), but a typo must not become the conversation's project and
     * break every later command.
     */
    fun resolveForShell(
        context: Context,
        sessionId: String,
        cwdArg: String?,
        persist: Boolean,
    ): Resolution {
        val requested = normalize(cwdArg) ?: return Resolution(dir = get(context, sessionId))
        validationError(requested)?.let { return Resolution(dir = null, error = it) }
        if (requested == DEFAULT_DIR) {
            if (persist) clear(context, sessionId)
            return Resolution(dir = null)
        }
        val host = hostDir(context, sessionId, requested)
        if (host != null && host.exists() && !host.isDirectory) {
            return Resolution(dir = null, error = "Error: 'cwd' is not a directory: $requested")
        }
        if (persist && host != null && host.isDirectory) set(context, sessionId, requested)
        return Resolution(dir = requested)
    }

    private fun hostDir(context: Context, sessionId: String, linuxPath: String): File? =
        try {
            PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
        } catch (_: Exception) {
            null
        }

    // ---------------------------------------------------------------- prompt

    /**
     * The first readable instructions file at the project root, as
     * (file name, truncated body). Null when there is none, when it is empty, or
     * when it cannot be read — a missing file is the normal case, not an error.
     */
    fun loadInstructions(context: Context, sessionId: String, dir: String): Pair<String, String>? {
        val base = dir.trimEnd('/')
        for (name in INSTRUCTION_FILES) {
            val linuxPath = "$base/$name"
            val file = hostDir(context, sessionId, linuxPath) ?: continue
            val text = try {
                if (!file.isFile || file.length() > MAX_INSTRUCTIONS_FILE_BYTES) continue
                file.readText()
            } catch (_: Exception) {
                continue
            }
            if (text.isBlank() || text.indexOf('\u0000') >= 0) continue
            return name to truncateInstructions(text, linuxPath)
        }
        return null
    }

    /** Files larger than this are not read at all (an instructions file is prose, not a dump). */
    private const val MAX_INSTRUCTIONS_FILE_BYTES = 1_000_000L

    /** The runtime-context block for [sessionId], or null when it has no project. */
    fun promptFragmentFor(context: Context, sessionId: String): String? {
        val dir = get(context, sessionId) ?: return null
        val loaded = loadInstructions(context, sessionId, dir)
        return promptFragment(dir, loaded?.first, loaded?.second)
    }
}
