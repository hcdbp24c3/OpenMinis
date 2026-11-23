package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [T-coding-m3-plan] Plan mode: the agent may look, but not touch.
 *
 * While it is on for a conversation the file-writing tools are taken out of the
 * schema, and `shell_execute` only runs commands [CommandRisk] classifies as
 * read-only. Both halves are enforced at dispatch as well, so a model that
 * calls a tool it was not offered — or a sub agent working for this
 * conversation — is refused rather than trusted.
 *
 * Stored per conversation in SharedPreferences, like [SessionProject].
 */
object PlanMode {
    const val PREFS = "session_plan_mode"

    /** Tools that change files; hidden and refused while planning. */
    val WRITE_TOOLS: Set<String> = setOf("file_write", "file_edit", "file_undo")

    const val PROMPT_FRAGMENT =
        "- Plan mode is ON. You may read and investigate, but you cannot change anything: the file-writing tools " +
            "are unavailable and shell_execute only runs read-only commands (anything else is refused).\n" +
            "- Work out what needs to change, then present a concrete plan: the files to touch, the change in each, " +
            "and how it will be verified. Do not try to work around the restriction.\n" +
            "- The user turns plan mode off themselves (the chip above the message box, or /plan). " +
            "If ask_user_question is available, end by asking whether to approve or revise the plan."

    private val _version = MutableStateFlow(0)

    /** Bumped on every change, so the UI can re-read [isOn]. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context, sessionId: String): Boolean {
        if (sessionId.isEmpty()) return false
        return try {
            prefs(context).getBoolean(sessionId, false)
        } catch (_: Exception) {
            false
        }
    }

    fun set(context: Context, sessionId: String, on: Boolean) {
        if (sessionId.isEmpty()) return
        try {
            val editor = prefs(context).edit()
            if (on) editor.putBoolean(sessionId, true) else editor.remove(sessionId)
            editor.apply()
        } catch (_: Exception) {
        }
        _version.value = _version.value + 1
    }

    /** Flip it; returns the new state. */
    fun toggle(context: Context, sessionId: String): Boolean {
        val next = !isOn(context, sessionId)
        set(context, sessionId, next)
        return next
    }

    // ------------------------------------------------------------------ pure

    fun filter(tools: List<AgentToolDefinition>, on: Boolean): List<AgentToolDefinition> =
        if (on) tools.filter { it.name !in WRITE_TOOLS } else tools

    /** The tool-result text when [toolName] may not run while planning; null when it may. */
    fun refusalFor(toolName: String, argsJson: String): String? {
        if (toolName in WRITE_TOOLS) {
            return "Refused: plan mode is on, so $toolName is unavailable. Describe the change in your plan instead; " +
                "the user turns plan mode off when they want it carried out."
        }
        if (toolName == "shell_execute") {
            val command = try {
                JSONObject(argsJson).optString("command", "")
            } catch (_: Exception) {
                ""
            }
            if (command.isBlank()) return null
            val verdict = CommandRisk.classify(command)
            if (verdict.level != CommandRisk.Level.READ_ONLY) {
                return "Refused: plan mode is on, and this command is not read-only. Only inspection commands run " +
                    "while planning (ls, cat, rg, git status/diff/log and the like, without redirection). " +
                    "Put the command in your plan instead."
            }
        }
        return null
    }

    // ---------------------------------------------------------------- hooks

    fun filterTools(context: Context, sessionId: String, tools: List<AgentToolDefinition>): List<AgentToolDefinition> =
        filter(tools, isOn(context, sessionId))

    fun refusal(context: Context, sessionId: String, toolName: String, argsJson: String): String? =
        if (isOn(context, sessionId)) refusalFor(toolName, argsJson) else null

    fun promptFragmentFor(context: Context, sessionId: String): String? =
        if (isOn(context, sessionId)) PROMPT_FRAGMENT else null
}
