package com.openminis.app.tools

import com.openminis.app.agent.ShadowGit
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-coding-m3-shadow] Lets the agent — and a reviewing sub agent — read what
 * this turn changed, as a diff against the snapshot taken before the turn's
 * first change. Read-only.
 */
object TurnChangesTool {
    const val NAME = "turn_changes"
    const val MAX_PATCH_CHARS = 30_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Show what has changed in the project directory since the current turn started: the list of " +
            "changed files with added/removed line counts, then a unified diff. Covers changes made by the file " +
            "tools and by shell commands; files ignored by the project's .gitignore are not included. " +
            "Use it to review your own work before you report it, and when reviewing a change as a sub agent " +
            "instead of hunting for what was edited. Read-only. Returns nothing useful before the first change " +
            "of the turn, or when the conversation has no project directory.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    /** The tool result for [changes]; null means there is no snapshot to compare against. */
    fun render(changes: ShadowGit.Changes?): String {
        if (changes == null) {
            return "No snapshot for this turn: nothing has been changed in a project directory yet, " +
                "or snapshots are unavailable for it."
        }
        if (changes.files.isEmpty()) return ShadowGit.summary(changes)
        val sb = StringBuilder()
        sb.append(ShadowGit.summary(changes)).append('\n')
        for (f in changes.files) {
            if (f.binary) sb.append("  (binary) ") else sb.append("  +").append(f.added).append(" -").append(f.removed).append(' ')
            sb.append(f.path).append('\n')
        }
        var patch = changes.patch
        var cut = changes.truncated
        if (patch.length > MAX_PATCH_CHARS) {
            patch = patch.take(MAX_PATCH_CHARS)
            cut = true
        }
        if (patch.isNotBlank()) sb.append('\n').append(patch)
        if (cut) sb.append("\n[diff truncated — read the remaining files directly]")
        return sb.toString()
    }

    suspend fun execute(argsJson: String, fsSessionId: String): ToolExecutionResult {
        val toolTitle = try {
            JSONObject(argsJson).optString("tool_title", NAME)
        } catch (_: Exception) {
            NAME
        }
        return ToolExecutionResult(render(ShadowGit.changes(fsSessionId)), true, toolTitle = toolTitle)
    }
}
