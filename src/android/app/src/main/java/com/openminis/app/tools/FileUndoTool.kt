package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-coding-m2-undo] Takes back the last `file_write` / `file_edit` change,
 * from the copy [FileJournal] kept just before it.
 */
object FileUndoTool {
    const val NAME = "file_undo"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Undo the most recent change made by file_write or file_edit in this conversation, restoring the file to what it was just before (or removing it if that change created it). Without `path` it undoes whichever file was changed last; with `path` it undoes the last change to that file. Call it again to step further back. Changes made by shell commands are not recorded and cannot be undone here. Use it when the user asks to undo or revert an edit, or when an edit you made turned out wrong.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Undo last edit to config')."),
            "path" to AgentToolParam("string", "Absolute Linux path of the file whose last change should be undone. Omit to undo the most recent change to any file."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", NAME)
            val path = if (args.has("path") && !args.isNull("path")) args.optString("path", "") else ""
            val result = FileJournal.undo(FileJournal.dir(context, sessionId), path)
            ToolExecutionResult(result.message, result.success, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error undoing file change: ${e.message}", false)
        }
    }
}
