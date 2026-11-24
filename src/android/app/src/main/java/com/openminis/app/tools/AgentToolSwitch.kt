package com.openminis.app.tools

import android.content.Context

/**
 * [T-tools-granular-switches] Per-tool switches for the agent's optional
 * capabilities, surfaced as Settings › Agent Runtime › Agent Tools.
 *
 * Port of iOS `AgentToolSwitch` (Views/Settings/ToolsSettingsView.swift). The
 * key strings are deliberately byte-identical to iOS so a future settings sync
 * carries a user's choice across platforms unchanged.
 *
 * The gate lives HERE, at the read, rather than only in the UI: the tool
 * schema builder, the loop's dispatcher and the CLI offload bridges all call
 * [isToolEnabled], so a stored `true` cannot re-enable a capability through a
 * path that skipped the settings screen.
 */
enum class AgentToolSwitch(val key: String, val defaultValue: Boolean) {
    /** browser_use — mature, on by default. */
    BROWSER("agent.tools.browser.enabled", true),

    /** delegate_task + agent_status — still maturing, but on by default on
     *  Android because delegation already shipped here under its own switch
     *  (see [migrateLegacyIfNeeded]); flipping the default would silently
     *  disable a feature existing users have. */
    AGENTS("agent.tools.agents.enabled", true),

    /** [T-android-web-search] web_search — on by default, because its default
     *  backend (DuckDuckGo) needs no key and the alternative for a fact is the
     *  browser tool, which is minutes of page-loading instead of one request. */
    WEB_SEARCH("agent.tools.websearch.enabled", true),

    /** [T-android-web-fetch] web_fetch — the dedicated reader for one URL, which
     *  replaces curling through the terminal or opening the page in the browser.
     *  On by default: it is the cheap path, and it is SSRF-guarded. */
    WEB_FETCH("agent.tools.webfetch.enabled", true),

    /** [T-android-repo-digest] repo_digest — read a GitHub repo as a digest.
     *  On by default: it is the cheap alternative to cloning or browsing. */
    REPO("agent.tools.repo.enabled", true),

    /** [T-port-user-ask] user_ask - on by default, as on iOS (UserAskSettings.defaultEnabled).
     *  [T-ask-merge] The only ask tool: the fork's ask_user_question and its
     *  switch (agent.tools.ask.enabled) were folded into it, see
     *  [migrateAskMergeIfNeeded]. */
    USER_ASK(com.openminis.app.agent.userask.UserAskSettingsSpec.ENABLED_KEY, com.openminis.app.agent.userask.UserAskSettingsSpec.DEFAULT_ENABLED),
    ;

    fun isEnabled(context: Context): Boolean {
        // [T-ask-merge] A read that somehow comes before app start still sees
        // the carried-over value.
        if (this == USER_ASK) migrateAskMergeIfNeeded(context)
        return prefs(context).getBoolean(key, defaultValue)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(key, enabled).apply()
    }

    companion object {
        const val PREFS = "agent_settings"

        internal fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The switch governing a tool name; null = always available. */
        fun governing(toolName: String): AgentToolSwitch? = when (toolName) {
            "browser_use" -> BROWSER
            "web_search" -> WEB_SEARCH
            "web_fetch" -> WEB_FETCH
            // [T-ask-merge] The removed ask tool's two names: a replayed call
            // from an older transcript is governed by the surviving switch.
            com.openminis.app.agent.userask.LegacyAskTool.NAME,
            com.openminis.app.agent.userask.LegacyAskTool.ALIAS -> USER_ASK
            "repo_digest" -> REPO
            // [T-sub-agents-v1] The renamed tool plus the two names
            // shipped builds wrote, so the switch still governs a
            // replayed call from an older transcript.
            "subagent_task", "delegate_task", "agent_status" -> AGENTS
            com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> USER_ASK
            else -> null
        }

        fun isToolEnabled(context: Context, toolName: String): Boolean =
            governing(toolName)?.isEnabled(context) ?: true

        // ---------------------------------------------------------------- migration

        /** In-memory latch so the hot read path checks the stored marker once per process. */
        @Volatile private var askMergeChecked = false

        /**
         * [T-ask-merge] ask_user_question was folded into user_ask, and so was
         * its switch. Once: a user who had turned the old switch OFF gets the
         * merged tool off (the decision is UserAskSwitchMigration.decide, unit
         * tested). The old key is left in place, unread from then on.
         */
        fun migrateAskMergeIfNeeded(context: Context) {
            if (askMergeChecked) return
            try {
                val m = com.openminis.app.agent.userask.UserAskSwitchMigration
                val p = prefs(context)
                fun stored(k: String): Boolean? =
                    if (p.contains(k)) runCatching { p.getBoolean(k, true) }.getOrNull() else null
                val d = m.decide(
                    alreadyMigrated = runCatching { p.getBoolean(m.MIGRATED_KEY, false) }.getOrDefault(false),
                    legacyAskEnabled = stored(m.LEGACY_ENABLED_KEY),
                    userAskEnabled = stored(USER_ASK.key),
                )
                if (d.markMigrated) {
                    val e = p.edit().putBoolean(m.MIGRATED_KEY, true)
                    val write: Boolean? = d.writeUserAskEnabled
                    if (write != null) e.putBoolean(USER_ASK.key, write)
                    e.apply()
                }
                askMergeChecked = true
            } catch (_: Exception) {
                // Never let a settings read fail because of the carry-over;
                // the next read tries again.
            }
        }

        private const val MIGRATION_KEY = "agent.tools.granular.v1.migrated"

        /**
         * Carry the pre-split choice forward, once.
         *
         * Android shipped delegation behind `AgentSettings.KEY_ENABLED`
         * ("agent.helpers.enabled"). That key is now one of two, so a user who
         * had explicitly turned delegation OFF must not silently get it back
         * when the new switch reads its own default. Only an explicitly stored
         * value migrates — an untouched install writes nothing and takes the
         * defaults.
         *
         * browser_use had no switch before and is on by default, so there is
         * nothing to carry over for it.
         */
        fun migrateLegacyIfNeeded(context: Context) {
            // [T-ask-merge] Independent of the granular migration below, so it
            // goes first: that one returns early once it has run.
            migrateAskMergeIfNeeded(context)
            val p = prefs(context)
            if (p.getBoolean(MIGRATION_KEY, false)) return
            p.edit().putBoolean(MIGRATION_KEY, true).apply()
            // Never clobber a value the user already set on the new key.
            if (p.contains(AGENTS.key)) return
            if (p.contains(com.openminis.app.agent.jobs.AgentSettings.KEY_ENABLED)) {
                val legacy = p.getBoolean(
                    com.openminis.app.agent.jobs.AgentSettings.KEY_ENABLED, true,
                )
                p.edit().putBoolean(AGENTS.key, legacy).apply()
            }
        }
    }
}
