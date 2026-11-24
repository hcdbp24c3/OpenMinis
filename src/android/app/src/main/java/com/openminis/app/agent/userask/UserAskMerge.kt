package com.openminis.app.agent.userask

/**
 * [T-ask-merge] The fork's `ask_user_question` tool (a modal sheet) and
 * upstream's `user_ask` (the in-chat card) did the same job, so the model saw
 * two ask tools. `user_ask` is the one that stays: it already covers what the
 * old tool did (1-4 questions, 2-4 options, multi-select, free-text Other) and
 * adds confirm questions, a timeout, recovery after a process kill, background
 * notifications and the deferred mode.
 *
 * What is left of the removed tool lives here, as pure logic:
 *  - [LegacyAskTool]: its names, kept so an old transcript still renders and a
 *    model that still calls it gets a tool error it can act on;
 *  - [UserAskSwitchMigration]: the one-time carry-over of its settings switch.
 */
object LegacyAskTool {
    /** The removed tool's name, as stored in old conversations. */
    const val NAME = "ask_user_question"

    /** The MCP spelling the removed tool also answered to. */
    const val ALIAS = "AskUserQuestion"

    val NAMES: Set<String> = setOf(NAME, ALIAS)

    fun isLegacy(toolName: String?): Boolean = toolName != null && toolName in NAMES

    /**
     * The tool error for a call to the removed tool. It names the tool to use
     * instead and the fields that differ, or - in a run that does not offer
     * `user_ask` (switch off, sub agent) - says that nobody can be asked.
     */
    fun redirectMessage(userAskExposed: Boolean): String =
        if (userAskExposed) {
            "Error: $NAME was removed; it is now ${UserAskPromptText.TOOL_NAME}. " +
                "Call ${UserAskPromptText.TOOL_NAME} with the same questions: `questions` is a JSON array of 1-4 objects, " +
                "each {\"type\":\"options\",\"question\":\"...\",\"options\":[{\"label\":\"...\",\"description\":\"...\"}]} " +
                "(use `multi_select`, not `multiSelect`; use \"type\":\"confirm\" with `impact` to approve one risky action)."
        } else {
            "Error: $NAME was removed, and ${UserAskPromptText.TOOL_NAME} is not available in this run. " +
                "Do not ask through a tool: choose the most reasonable option yourself and state the assumption."
        }
}

/**
 * [T-ask-merge] One switch instead of two. The removed tool had its own
 * switch (`agent.tools.ask.enabled`); a user who turned EITHER tool off must
 * end up with the merged tool off. Runs once: afterwards the surviving switch
 * alone decides, so turning it back on is not undone by the stale old value.
 */
object UserAskSwitchMigration {
    /** The removed switch's key. Read once, never written or deleted. */
    const val LEGACY_ENABLED_KEY = "agent.tools.ask.enabled"

    /** Set once the carry-over ran. */
    const val MIGRATED_KEY = "agent.tools.askMerge.v1.migrated"

    /**
     * @property markMigrated write [MIGRATED_KEY] = true
     * @property writeUserAskEnabled value to store under the surviving key
     *   ([UserAskSettingsSpec.ENABLED_KEY]); null = leave that key untouched
     */
    data class Decision(val markMigrated: Boolean, val writeUserAskEnabled: Boolean?)

    /**
     * @param alreadyMigrated the stored [MIGRATED_KEY]
     * @param legacyAskEnabled the stored legacy value, null when never stored
     * @param userAskEnabled the stored surviving value, null when never stored
     */
    fun decide(alreadyMigrated: Boolean, legacyAskEnabled: Boolean?, userAskEnabled: Boolean?): Decision {
        if (alreadyMigrated) return Decision(markMigrated = false, writeUserAskEnabled = null)
        // Only an explicit OFF on the removed switch carries over, and only if
        // the surviving one is not already off. An untouched install, or one
        // that had the old switch on, writes nothing and keeps user_ask's own
        // value or default.
        val turnOff = legacyAskEnabled == false && userAskEnabled != false
        return Decision(markMigrated = true, writeUserAskEnabled = if (turnOff) false else null)
    }

    /** What the merged switch reads after [decide] was applied (for tests and reasoning). */
    fun effectiveEnabled(legacyAskEnabled: Boolean?, userAskEnabled: Boolean?): Boolean {
        val d = decide(false, legacyAskEnabled, userAskEnabled)
        return d.writeUserAskEnabled ?: userAskEnabled ?: UserAskSettingsSpec.DEFAULT_ENABLED
    }
}
