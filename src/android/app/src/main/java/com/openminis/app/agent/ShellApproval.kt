package com.openminis.app.agent

import android.content.Context
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.confirm.ConfigConfirmationGate
import com.openminis.app.config.confirm.ConfirmOutcome
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.config.confirm.PendingConfigChangeItem

/**
 * [T-coding-m2-approval] Stops the agent's shell before a command that could
 * cost the user work, and asks.
 *
 * Two switches (Settings › Agent Tools):
 *  - risky commands: on by default. Only what [CommandRisk] calls DANGEROUS.
 *  - every command that is not read-only: off by default, for a careful session.
 *
 * The question is put through [ConfigConfirmationGate] on purpose. That gate
 * already is what an approval on a phone needs — one dialog at a time, mounted
 * at the app root, a 120 s timeout, and a notification when the app is in the
 * background — so the command rides it as a one-row change instead of growing a
 * second queue, dialog and notifier beside it.
 *
 * Only the agent's `shell_execute` comes through here. The in-app terminal is
 * the user typing, and is never gated.
 */
object ShellApproval {
    const val PREFS = "shell_approval"
    const val KEY_CONFIRM_RISKY = "shell.approval.risky.enabled"
    const val KEY_CONFIRM_CHANGES = "shell.approval.changes.enabled"

    /** Longest command shown in full in the dialog and the notification. */
    const val CAPTION_MAX_CHARS = 1_500

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun confirmRisky(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_RISKY, true)

    fun setConfirmRisky(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_RISKY, enabled).apply()
    }

    fun confirmChanges(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_CHANGES, false)

    fun setConfirmChanges(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_CHANGES, enabled).apply()
    }

    // ------------------------------------------------------------------ pure

    /** True when the user must be asked before a command of this [level] runs. */
    fun needsApproval(
        level: CommandRisk.Level,
        confirmRisky: Boolean,
        confirmChanges: Boolean,
    ): Boolean = when (level) {
        CommandRisk.Level.READ_ONLY -> false
        CommandRisk.Level.NORMAL -> confirmChanges
        CommandRisk.Level.DANGEROUS -> confirmRisky || confirmChanges
    }

    fun caption(command: String): String {
        val trimmed = command.trim()
        return if (trimmed.length <= CAPTION_MAX_CHARS) {
            trimmed
        } else {
            trimmed.take(CAPTION_MAX_CHARS) + "\n… (${trimmed.length - CAPTION_MAX_CHARS} more characters)"
        }
    }

    fun pendingChange(command: String, verdict: CommandRisk.Verdict, dir: String): PendingConfigChange =
        PendingConfigChange(
            items = listOf(
                PendingConfigChangeItem(
                    displayName = "Shell command",
                    path = "in $dir",
                    oldDisplay = "",
                    newDisplay = verdict.reason ?: "changes files or state",
                    verb = "run",
                    risk = if (verdict.level == CommandRisk.Level.DANGEROUS) {
                        ConfigRisk.DESTRUCTIVE
                    } else {
                        ConfigRisk.SENSITIVE
                    },
                ),
            ),
            caption = caption(command),
        )

    fun subAgentRefusal(verdict: CommandRisk.Verdict): String =
        "Error: this command was NOT run. It needs the user's approval" +
            (verdict.reason?.let { " ($it)" } ?: "") +
            ", and a sub agent cannot ask the user. Do not retry it or work around it: " +
            "report it to the parent agent, which can run it with the user's approval."

    const val DECLINED_MESSAGE =
        "Error: the user declined this command. It was NOT run. Do not retry it and do not " +
            "reach the same result another way; tell the user what you wanted to do and ask how to proceed."

    val TIMED_OUT_MESSAGE: String
        get() = "Error: this command needs the user's approval and they did not answer within " +
            "${ConfigConfirmationGate.TIMEOUT_MS / 1000} seconds. It was NOT run. Do not retry it in a loop; " +
            "tell the user what is waiting on them."

    // ------------------------------------------------------------------ gate

    /**
     * Returns null when the command may run, or the tool-result text explaining
     * why it did not. Suspends on the user's answer when one is needed.
     */
    suspend fun check(
        context: Context,
        command: String,
        dir: String,
        isSubAgent: Boolean,
    ): String? {
        val risky = confirmRisky(context)
        val changes = confirmChanges(context)
        if (!risky && !changes) return null
        val verdict = CommandRisk.classify(command)
        if (!needsApproval(verdict.level, risky, changes)) return null
        if (isSubAgent) return subAgentRefusal(verdict)
        return when (val outcome = ConfigConfirmationGate.requestConfirmation(pendingChange(command, verdict, dir))) {
            is ConfirmOutcome.Approved -> if (outcome.items.any { it.isApproved }) null else DECLINED_MESSAGE
            ConfirmOutcome.Rejected -> DECLINED_MESSAGE
            ConfirmOutcome.TimedOut -> TIMED_OUT_MESSAGE
        }
    }
}
