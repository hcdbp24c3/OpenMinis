package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-default-thinking] The thinking level a conversation starts with when
 * nothing more specific has chosen one.
 *
 * Order of precedence, most specific first:
 *  1. the level the user picked inside the conversation (persisted on the
 *     session row as `thinking_override`),
 *  2. the model group's own `defaultThinkingLevel`,
 *  3. this app-wide default,
 *  4. OFF — what every build before this setting did.
 *
 * The setting only ever fills a gap; it never replaces 1 or 2.
 *
 * Stored in its own plain SharedPreferences file as the enum NAME, the same
 * spelling the session row uses. A missing or unreadable value reads as OFF so
 * an install that never opened the setting behaves exactly as before.
 */
object DefaultThinking {
    const val PREFS = "agent_default_thinking"
    const val KEY_LEVEL = "default.thinking.level"

    /** Picker order: OFF first (the shipped behaviour), then AUTO, then the tiers. */
    val CHOICES: List<ThinkingLevel> = listOf(
        ThinkingLevel.OFF,
        ThinkingLevel.AUTO,
        ThinkingLevel.LOW,
        ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH,
        ThinkingLevel.XHIGH,
        ThinkingLevel.MAX,
        ThinkingLevel.ULTRA,
    )

    /** Stored text -> level. Null, blank or unknown text is OFF. */
    fun parse(raw: String?): ThinkingLevel {
        if (raw == null || raw.isBlank()) return ThinkingLevel.OFF
        return ThinkingLevel.parseOrNull(raw) ?: ThinkingLevel.OFF
    }

    fun get(context: Context): ThinkingLevel =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEVEL, null))

    fun set(context: Context, level: ThinkingLevel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LEVEL, level.name)
            .apply()
    }

    /**
     * Which level a session should START with. [persisted] is the session's own
     * stored choice, [groupDefault] the bound model group's default; either may
     * be null. Returns null when one of those two decides — the caller already
     * applied it and must leave it alone — and the app-wide default otherwise.
     */
    fun fallbackFor(
        persisted: ThinkingLevel?,
        groupDefault: ThinkingLevel?,
        userDefault: ThinkingLevel,
    ): ThinkingLevel? {
        if (persisted != null) return null
        if (groupDefault != null) return null
        return userDefault
    }

    /**
     * Fit [level] to what the bound model can do.
     *
     *  - a model that does not reason, or whose ceiling is OFF, gets OFF;
     *  - AUTO passes through: it sends no tier, so there is nothing to clamp;
     *  - a tier above the model's ceiling is lowered to the ceiling;
     *  - anything else is kept.
     */
    fun clampToModel(
        level: ThinkingLevel,
        supportsReasoning: Boolean,
        ceiling: ThinkingLevel,
    ): ThinkingLevel {
        if (!supportsReasoning) return ThinkingLevel.OFF
        if (ceiling == ThinkingLevel.OFF) return ThinkingLevel.OFF
        if (level == ThinkingLevel.AUTO) return ThinkingLevel.AUTO
        if (level.rank > ceiling.rank) return ceiling
        return level
    }

    /** Label for the settings row and its picker. */
    fun label(level: ThinkingLevel): String = level.displayName
}
