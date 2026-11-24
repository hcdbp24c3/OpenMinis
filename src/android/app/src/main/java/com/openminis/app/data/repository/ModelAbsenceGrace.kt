package com.openminis.app.data.repository

import android.content.Context

/**
 * [T-model-absence-grace-days] How long a catalog model the provider stopped
 * listing is kept (greyed out as unavailable) before a refresh removes it.
 *
 * Upstream fixed this at [ProviderRepository.MODEL_ABSENCE_GRACE_MS] (7 days).
 * That constant is still the default; this only lets the user pick another
 * number of days. 0 means "remove on the refresh that no longer lists it",
 * which is the behaviour from before the grace window existed.
 *
 * Hand-added (isCustom) models and voice-template seeds never reach this
 * decision: replaceEntries filters them out before asking.
 *
 * The value is read from SharedPreferences once per process and cached, so
 * replaceEntries (which runs under configLock) does no I/O after the first call.
 */
object ModelAbsenceGrace {
    private const val PREFS = "model_absence_grace"
    private const val KEY_DAYS = "grace.days"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    const val MIN_DAYS = 0
    const val MAX_DAYS = 90
    val DEFAULT_DAYS: Int = (ProviderRepository.MODEL_ABSENCE_GRACE_MS / DAY_MS).toInt()

    @Volatile
    private var cached: Int? = null

    fun clampDays(days: Int): Int = days.coerceIn(MIN_DAYS, MAX_DAYS)

    /** Parses what the user typed. Blank or not a number -> null (keep the old value). */
    fun parseDays(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 6 || !t.all { it in '0'..'9' }) return null
        return clampDays(t.toInt())
    }

    fun graceMs(days: Int): Long = clampDays(days) * DAY_MS

    /**
     * The decision for one unlisted catalog entry.
     *
     * [absentSince] is null on the first refresh that misses the model. With
     * 0 days that first miss already drops it; otherwise the first miss is
     * always kept (elapsed absence is zero) and the entry goes once the
     * absence is strictly longer than the window, exactly as the fixed
     * 7-day rule did.
     */
    fun shouldDrop(absentSince: Long?, nowMs: Long, days: Int): Boolean {
        val d = clampDays(days)
        if (d == 0) return true
        val since = absentSince ?: nowMs
        return nowMs - since > d * DAY_MS
    }

    fun label(days: Int): String = when (val d = clampDays(days)) {
        0 -> "Remove right away"
        1 -> "1 day"
        else -> "$d days"
    }

    fun days(context: Context): Int {
        cached?.let { return it }
        val stored = try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DAYS, DEFAULT_DAYS)
        } catch (_: Throwable) {
            DEFAULT_DAYS
        }
        return clampDays(stored).also { cached = it }
    }

    fun set(context: Context, days: Int) {
        val d = clampDays(days)
        cached = d
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DAYS, d)
            .apply()
    }
}
