package com.openminis.app.tools

import android.content.Context

/**
 * [T-android-repo-digest] Per-host git credentials for [RepoDigestTool].
 *
 * One field per family rather than one token for everything, because the schemes
 * genuinely differ: GitHub and Bitbucket take a Bearer token, GitLab wants
 * `PRIVATE-TOKEN`, and Gitea/Forgejo expect `token …`. A single field would either
 * be sent with a header some hosts reject or invite users to paste a credential
 * into the wrong slot.
 *
 * Tokens live in settings rather than in the tool call on purpose: the model
 * should never have to be told a credential (and would then be the one putting it
 * in a transcript). Without any token, public repositories on every supported host
 * still work.
 */
object RepoDigestPrefs {
    const val PREFS = "repo_digest_prefs"

    const val KEY_GITHUB = "github_token"
    const val KEY_GITLAB = "gitlab_token"
    const val KEY_GITEA = "gitea_token"
    const val KEY_BITBUCKET = "bitbucket_token"

    private fun keyFor(provider: RepoDigestTool.Provider): String? = when (provider) {
        RepoDigestTool.Provider.GITHUB -> KEY_GITHUB
        RepoDigestTool.Provider.GITLAB -> KEY_GITLAB
        RepoDigestTool.Provider.GITEA -> KEY_GITEA
        RepoDigestTool.Provider.BITBUCKET -> KEY_BITBUCKET
        // An unrecognised host has to be probed before we know which token applies,
        // so any configured token (Gitea first: most self-hosted forges are
        // Gitea/Forgejo) is offered to the probe. The tool re-reads the exact
        // provider's token once the probe identifies it.
        RepoDigestTool.Provider.UNKNOWN -> null
    }

    /** The token for one provider, or the first configured one for an unknown host. */
    fun token(context: Context?, provider: RepoDigestTool.Provider): String {
        if (context == null) return ""
        keyFor(provider)?.let { return read(context, it) }
        for (candidate in listOf(KEY_GITEA, KEY_GITLAB, KEY_BITBUCKET, KEY_GITHUB)) {
            val value = read(context, candidate)
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    fun setToken(context: Context, provider: RepoDigestTool.Provider, value: String) {
        val key = keyFor(provider) ?: return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value.trim())
            .apply()
    }

    fun hasToken(context: Context?, provider: RepoDigestTool.Provider): Boolean =
        token(context, provider).isNotEmpty()

    private fun read(context: Context, key: String): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
            .orEmpty()
            .trim()
}
