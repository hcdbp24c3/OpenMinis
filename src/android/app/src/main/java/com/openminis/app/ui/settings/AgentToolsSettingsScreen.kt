package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.agent.ShellApproval
import com.openminis.app.tools.AgentToolSwitch

/**
 * [T-tools-granular-switches] Settings › Agent Runtime › Tools — port of iOS
 * `ToolsSettingsView`.
 *
 * One switch per optional capability. The switches gate the tool SCHEMA, so a
 * disabled tool is not merely refused at call time — the model never sees it
 * and cannot plan around it. The remaining tools (shell, files, memory) are
 * core to the agent and deliberately have no switch.
 *
 * The title stays "Tools" in English to sit alongside Skills and Memory in the
 * Agent Runtime section; locales whose bare "tools" reads as hardware render
 * it as "available tools" instead.
 */
@Composable
fun AgentToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var browserEnabled by remember {
        mutableStateOf(AgentToolSwitch.BROWSER.isEnabled(context))
    }
    // [T-android-web-search] web_search — off/on here gates the tool SCHEMA and
    // the dispatcher alike; its engine and API keys live on Settings › Web search.
    var webSearchEnabled by remember {
        mutableStateOf(AgentToolSwitch.WEB_SEARCH.isEnabled(context))
    }
    // [T-android-web-fetch] web_fetch — the one-URL reader.
    var webFetchEnabled by remember {
        mutableStateOf(AgentToolSwitch.WEB_FETCH.isEnabled(context))
    }
    // [T-android-repo-digest] repo_digest — read a GitHub repo in one call.
    var repoDigestEnabled by remember {
        mutableStateOf(AgentToolSwitch.REPO.isEnabled(context))
    }
    // [T-android-ask-user] ask_user_question — the run pauses for an answer.
    var askEnabled by remember {
        mutableStateOf(AgentToolSwitch.ASK.isEnabled(context))
    }

    // [T-coding-m2-approval] When the agent's shell stops and asks first.
    var confirmRisky by remember {
        mutableStateOf(ShellApproval.confirmRisky(context))
    }
    var confirmChanges by remember {
        mutableStateOf(ShellApproval.confirmChanges(context))
    }
    // [T-coding-m3-shadow] Snapshot the project before the agent changes it.
    var shadowSnapshots by remember {
        mutableStateOf(com.openminis.app.agent.ShadowGit.isEnabled(context))
    }
    var userAskEnabled by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.isEnabled(context))
    }
    var userAskTimeout by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.timeoutMinutes(context))
    }
    var userAskNotify by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.notifyInBackground(context))
    }
    var showWaitPicker by remember { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.settings_agent_tools), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.agent_tools_browser_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_browser),
                checked = browserEnabled,
                onCheckedChange = {
                    browserEnabled = it
                    AgentToolSwitch.BROWSER.setEnabled(context, it)
                },
                showDivider = true,
            )
            // [T-android-web-search] Same section, because the choice is the same
            // kind of choice: may the agent reach the network on its own. The
            // difference is cost — one request instead of driving a WebView.
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_web_search),
                checked = webSearchEnabled,
                onCheckedChange = {
                    webSearchEnabled = it
                    AgentToolSwitch.WEB_SEARCH.setEnabled(context, it)
                },
                showDivider = true,
            )
            // [T-android-web-fetch] Same section: one more way the agent may reach
            // the network, and the cheapest one for reading a single page.
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_web_fetch),
                checked = webFetchEnabled,
                onCheckedChange = {
                    webFetchEnabled = it
                    AgentToolSwitch.WEB_FETCH.setEnabled(context, it)
                },
                showDivider = true,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_repo_digest),
                checked = repoDigestEnabled,
                onCheckedChange = {
                    repoDigestEnabled = it
                    AgentToolSwitch.REPO.setEnabled(context, it)
                },
                showDivider = true,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_ask),
                checked = askEnabled,
                onCheckedChange = {
                    askEnabled = it
                    AgentToolSwitch.ASK.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        // [T-coding-m2-approval] Not a tool switch but the same kind of choice:
        // how far the agent may go on its own. Strings are literals for now,
        // like ConfigConfirmDialog's, until the wording settles.
        SettingsSection(
            header = "Shell approval",
            footer = "Risky commands are recursive deletes of whole folders, forced pushes, " +
                "git commands that discard work, and downloads piped into a shell. " +
                "Commands you type in the terminal are never held back.",
        ) {
            SettingsSwitchRow(
                title = "Ask before risky commands",
                checked = confirmRisky,
                onCheckedChange = {
                    confirmRisky = it
                    ShellApproval.setConfirmRisky(context, it)
                },
                showDivider = true,
            )
            SettingsSwitchRow(
                title = "Ask before any command that changes things",
                subtitle = "Read-only commands such as ls, rg and git status still run without asking.",
                checked = confirmChanges,
                onCheckedChange = {
                    confirmChanges = it
                    ShellApproval.setConfirmChanges(context, it)
                },
                showDivider = true,
            )
            SettingsSwitchRow(
                title = "Snapshot the project before changes",
                subtitle = "Lets you review and revert a whole turn. Uses a hidden git repository, never your own.",
                checked = shadowSnapshots,
                onCheckedChange = {
                    shadowSnapshots = it
                    com.openminis.app.agent.ShadowGit.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        // [T-port-user-ask] user_ask: on/off plus how long a question waits
        // (iOS ToolsSettingsView.userAskSections), and whether a question asked
        // while the app is away is posted as a notification.
        SettingsSection(footer = stringResource(R.string.user_ask_setting_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.user_ask_setting_title),
                checked = userAskEnabled,
                onCheckedChange = {
                    userAskEnabled = it
                    com.openminis.app.agent.userask.UserAskSettings.setEnabled(context, it)
                },
                showDivider = userAskEnabled,
            )
            if (userAskEnabled) {
                SettingsValueRow(
                    title = stringResource(R.string.user_ask_setting_wait),
                    value = userAskWaitLabel(userAskTimeout),
                    onClick = { showWaitPicker = true },
                    showDivider = true,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.user_ask_setting_notify),
                    checked = userAskNotify,
                    onCheckedChange = {
                        userAskNotify = it
                        com.openminis.app.agent.userask.UserAskSettings.setNotifyInBackground(context, it)
                    },
                    showDivider = false,
                )
            }
        }
        if (showWaitPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showWaitPicker = false },
                title = { androidx.compose.material3.Text(stringResource(R.string.user_ask_setting_wait)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        for (m in com.openminis.app.agent.userask.UserAskSettingsSpec.TIMEOUT_CHOICES) {
                            SettingsChoiceRow(
                                title = userAskWaitLabel(m),
                                selected = m == userAskTimeout,
                                onSelect = {
                                    userAskTimeout = m
                                    com.openminis.app.agent.userask.UserAskSettings.setTimeoutMinutes(context, m)
                                    showWaitPicker = false
                                },
                                showDivider = false,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showWaitPicker = false }) {
                        androidx.compose.material3.Text(stringResource(R.string.user_ask_cancel))
                    }
                },
            )
        }
        // [T-android-subagent-settings-parity] The agents switch is NOT here.
        //
        // It lives on Settings > Agents > Sub Agents, which is also where the
        // roster is configured — one place to look, and no second copy of the
        // switch to drift out of sync with it. Mirrors iOS, whose
        // ToolsSettingsView likewise only migrates the legacy key and shows no
        // agents row.
    }
}

/** "5 min", or "Indefinitely" for 0. */
@Composable
private fun userAskWaitLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.user_ask_setting_indefinitely)
    else stringResource(R.string.user_ask_setting_minutes, minutes)
