package com.openminis.app.tools

import android.content.Context

/**
 * [T-android-repo-digest] GitHub credentials for [RepoDigestTool].
 *
 * The token lives in settings rather than in the tool call on purpose: the model
 * should never have to be told a credential (and would then be the one putting it
 * in a transcript). With a token, the GitHub API rate limit rises from 60 to 5000
 * requests/hour and private repositories become readable; without one, public
 * repos still work.
 */
object RepoDigestPrefs {
    const val PREFS = "repo_digest_prefs"
    const val KEY_TOKEN = "github_token"

    fun token(context: Context?): String {
        if (context == null) return ""
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
            .orEmpty()
            .trim()
    }

    fun setToken(context: Context, value: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, value.trim())
            .apply()
    }

    fun hasToken(context: Context?): Boolean = token(context).isNotEmpty()
}
