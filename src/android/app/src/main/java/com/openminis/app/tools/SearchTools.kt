package com.openminis.app.tools

import android.content.Context
import com.openminis.app.agent.SessionProject
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.ExecutionCoordinator
import org.json.JSONObject

/**
 * [T-coding-m4-search] `grep` and `glob`: ripgrep behind two small tools.
 *
 * The model could already call `rg` through shell_execute. These exist because
 * a tool call has no shell quoting to get wrong, the result is always bounded,
 * and both respect the project's .gitignore by default. They run in the
 * conversation's project directory when it has one. Read-only.
 */
object SearchTools {
    const val GREP = "grep"
    const val GLOB = "glob"

    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 500
    const val TIMEOUT_MS = 30_000L
    const val MAX_OUTPUT_CHARS = 40_000

    fun grepDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GREP,
        description = "Search file contents with a regular expression (ripgrep). Returns matching lines as " +
            "path:line:text, at most `limit` of them. Searches the project directory unless `path` is given; " +
            "files ignored by .gitignore and hidden files are skipped. Prefer this over running rg or grep " +
            "through shell_execute: nothing to quote, and the output is always bounded.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "pattern" to AgentToolParam("string", "Regular expression to search for (Rust regex syntax). Use fixed_strings for a literal."),
            "path" to AgentToolParam("string", "File or directory to search. Absolute, or relative to the project directory. Default: the project directory."),
            "glob" to AgentToolParam("string", "Only search files matching this glob, e.g. '*.kt' or 'src/**/*.ts'."),
            "ignore_case" to AgentToolParam("boolean", "Case-insensitive search. Default false."),
            "fixed_strings" to AgentToolParam("boolean", "Treat pattern as a literal string, not a regex. Default false."),
            "limit" to AgentToolParam("integer", "Maximum number of matching lines to return (default $DEFAULT_LIMIT, max $MAX_LIMIT)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "path", "glob", "ignore_case", "fixed_strings", "limit"),
    )

    fun globDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GLOB,
        description = "List files whose path matches a glob (ripgrep --files), sorted by path, at most `limit` of " +
            "them. Lists the project directory unless `path` is given; files ignored by .gitignore and hidden " +
            "files are skipped. Use it to find files by name or extension before reading them.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "pattern" to AgentToolParam("string", "Glob to match, e.g. '*.kt', '**/build.gradle*' or 'src/**/*Test.java'."),
            "path" to AgentToolParam("string", "Directory to list. Absolute, or relative to the project directory. Default: the project directory."),
            "limit" to AgentToolParam("integer", "Maximum number of paths to return (default $DEFAULT_LIMIT, max $MAX_LIMIT)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "path", "limit"),
    )

    // ------------------------------------------------------------------ pure

    fun clampLimit(raw: Int): Int = if (raw <= 0) DEFAULT_LIMIT else minOf(raw, MAX_LIMIT)

    private fun q(s: String): String = SessionProject.shellQuote(s)

    private fun text(args: JSONObject, key: String): String =
        if (args.has(key) && !args.isNull(key)) args.optString(key, "").trim() else ""

    /** Null means the path may be used; otherwise the error to return. */
    fun pathError(path: String): String? {
        if (path.isEmpty()) return null
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\u0000') >= 0) {
            return "Error: 'path' must be a single line."
        }
        return null
    }

    /**
     * The script for a grep call, or an error message (first = null). One extra
     * line is requested so a full page can be told from a truncated one.
     */
    fun grepScript(args: JSONObject, dir: String): Pair<String?, String?> {
        val pattern = if (args.has("pattern") && !args.isNull("pattern")) args.optString("pattern", "") else ""
        if (pattern.isEmpty()) return null to "Error: 'pattern' is required."
        val path = text(args, "path")
        pathError(path)?.let { return null to it }
        val glob = text(args, "glob")
        val limit = clampLimit(args.optInt("limit", DEFAULT_LIMIT))
        val sb = StringBuilder()
        sb.append("cd ").append(q(dir)).append(" || exit 97\n")
        sb.append("rg --line-number --no-heading --color never --max-columns 300 --max-columns-preview")
        if (args.optBoolean("ignore_case", false)) sb.append(" -i")
        if (args.optBoolean("fixed_strings", false)) sb.append(" -F")
        if (glob.isNotEmpty()) sb.append(" -g ").append(q(glob))
        sb.append(" -e ").append(q(pattern)).append(" -- ").append(q(if (path.isEmpty()) "." else path))
        sb.append(" 2>&1 | head -n ").append(limit + 1).append('\n')
        return sb.toString() to null
    }

    fun globScript(args: JSONObject, dir: String): Pair<String?, String?> {
        val pattern = text(args, "pattern")
        if (pattern.isEmpty()) return null to "Error: 'pattern' is required."
        val path = text(args, "path")
        pathError(path)?.let { return null to it }
        val limit = clampLimit(args.optInt("limit", DEFAULT_LIMIT))
        val sb = StringBuilder()
        sb.append("cd ").append(q(dir)).append(" || exit 97\n")
        sb.append("rg --files --sort path --color never -g ").append(q(pattern))
        sb.append(" -- ").append(q(if (path.isEmpty()) "." else path))
        sb.append(" 2>&1 | head -n ").append(limit + 1).append('\n')
        return sb.toString() to null
    }

    /** Trims the page to [limit] lines and says so when there was more. */
    fun render(output: String, limit: Int, emptyMessage: String): String {
        val lines = output.lines().map { it.removePrefix("./") }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyMessage
        val shown = lines.take(limit)
        var body = shown.joinToString("\n")
        var note = ""
        if (lines.size > limit) note = "\n[more results not shown — narrow the pattern, path or glob, or raise limit]"
        if (body.length > MAX_OUTPUT_CHARS) {
            body = body.take(MAX_OUTPUT_CHARS)
            note = "\n[output truncated — narrow the pattern, path or glob]"
        }
        return body + note
    }

    // ------------------------------------------------------------------- run

    suspend fun execute(
        name: String,
        argsJson: String,
        shellSessionId: String,
        fsSessionId: String,
        context: Context,
    ): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", name)
            val dir = SessionProject.get(context, fsSessionId) ?: SessionProject.DEFAULT_DIR
            val built = if (name == GLOB) globScript(args, dir) else grepScript(args, dir)
            val script = built.first ?: return ToolExecutionResult(built.second ?: "Error: invalid arguments.", false, toolTitle = toolTitle)
            val limit = clampLimit(args.optInt("limit", DEFAULT_LIMIT))
            val result = ExecutionCoordinator.execute(
                sessionId = shellSessionId,
                command = script,
                timeout = TIMEOUT_MS,
                fsSessionId = fsSessionId,
            )
            if (result.exitCode == 97) {
                ToolExecutionResult("Error: the project directory $dir is not available.", false, toolTitle = toolTitle)
            } else {
                val empty = if (name == GLOB) "No files match." else "No matches."
                ToolExecutionResult(render(result.output, limit, empty), true, toolTitle = toolTitle)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult("Error running $name: ${e.message}", false)
        }
    }
}
