package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
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
                showDivider = false,
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
