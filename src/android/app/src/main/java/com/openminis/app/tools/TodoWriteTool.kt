package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-coding-m2-todo] The agent's task list for multi-step work. Every call
 * carries the whole list, which keeps the tool stateless for the model and the
 * card above the composer trivially in sync.
 */
object TodoWriteTool {
    const val NAME = "todo_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = """Keep a short task list for multi-step work. The user sees it as a card above the message box, so it is how they follow your progress.

Use it when a job has three or more distinct steps, or when the user gives you several things to do. Skip it for a single step or a plain question.

Every call REPLACES the whole list: always send every item, not just the changed one. Each item is {"content": "what to do, in a few words", "status": "pending" | "in_progress" | "done"}. Mark an item in_progress when you start it and done the moment it is finished — do not save updates up. Keep one item in_progress at a time. Leave an item out to drop it; send [] to clear the list when the work is over or abandoned.""",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Mark tests as done')."),
            "todos" to AgentToolParam(
                "string",
                "JSON array of {content, status} objects — the full list (see tool description). Also accepted as a JSON array value.",
            ),
        ),
        required = listOf("tool_title", "todos"),
        propertyOrdering = listOf("tool_title", "todos"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context?, persist: Boolean): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", NAME)
            val parsed = TodoList.parse(args)
            val error = parsed.error
            if (error != null) {
                ToolExecutionResult(error, false, toolTitle = toolTitle)
            } else {
                TodoStore.set(context, sessionId, parsed.items, persist)
                ToolExecutionResult(TodoList.render(parsed.items), true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error updating the todo list: ${e.message}", false)
        }
    }
}
