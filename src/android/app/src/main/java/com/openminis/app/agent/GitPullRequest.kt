package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.gitvault.GitVault
import org.json.JSONObject

/**
 * [T-coding-m4-pr] Opens a pull request (merge request on GitLab) through the
 * host's REST API, with the token the Git Vault holds for that host.
 *
 * The token is read at the moment the user taps Create, sent only to the API
 * of the host the remote points at, over HTTPS, and never logged or returned.
 * Bitbucket and hosts that cannot be recognised are left to the browser.
 */
object GitPullRequest {
    enum class Provider { GITHUB, GITLAB, GITEA }

    data class Repo(val host: String, val slug: String)

    data class Call(val url: String, val authHeader: String, val authPrefix: String, val body: String)

    data class Result(val ok: Boolean, val url: String?, val message: String)

    const val TITLE_MAX_CHARS = 300
    const val BODY_MAX_CHARS = 20_000

    // ------------------------------------------------------------------ pure

    /** host + owner/repo out of an https, ssh:// or scp-style remote. */
    fun parseRemote(remote: String): Repo? {
        var r = remote.trim()
        if (r.isEmpty() || r.any { it == ' ' || it == '\n' || it == '\r' }) return null
        val host: String
        val path: String
        val scheme = r.indexOf("://")
        if (scheme < 0) {
            // scp style: user@host:owner/repo.git
            val at = r.indexOf('@')
            val colon = r.indexOf(':')
            if (at < 0 || colon < at) return null
            host = r.substring(at + 1, colon)
            path = r.substring(colon + 1)
        } else {
            r = r.substring(scheme + 3)
            val slash = r.indexOf('/')
            if (slash < 0) return null
            val authority = r.substring(0, slash).substringAfterLast('@')
            host = authority.substringBefore(':')
            path = r.substring(slash + 1)
        }
        val slug = path.trim('/').removeSuffix(".git")
        if (host.isEmpty() || !slug.contains('/') || slug.startsWith("/")) return null
        return Repo(host.lowercase().trimEnd('.'), slug)
    }

    /** Same host rules as repo_digest, minus the ones that need a pasted URL. */
    fun providerFor(host: String): Provider? {
        val h = host.lowercase()
        return when {
            h == "github.com" -> Provider.GITHUB
            h == "gitlab.com" || h.endsWith(".gitlab.com") || h.contains("gitlab") -> Provider.GITLAB
            h == "codeberg.org" || h.contains("gitea") || h.contains("forgejo") || h.contains("gogs") -> Provider.GITEA
            else -> null
        }
    }

    /** Null when the request can be sent; otherwise what the user must fix. */
    fun inputError(title: String, head: String, base: String): String? {
        if (title.isBlank()) return "Give the pull request a title."
        if (head.isBlank()) return "There is no current branch to open a pull request from."
        if (base.isBlank()) return "Say which branch to merge into."
        if (head.trim() == base.trim()) return "The branch to merge into must differ from the current branch."
        return null
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun buildCall(provider: Provider, repo: Repo, title: String, body: String, head: String, base: String): Call {
        val t = title.trim().take(TITLE_MAX_CHARS)
        val b = body.trim().take(BODY_MAX_CHARS)
        val slugPath = repo.slug.split('/').joinToString("/") { enc(it) }
        return when (provider) {
            Provider.GITHUB -> Call(
                "https://api.github.com/repos/$slugPath/pulls",
                "Authorization",
                "Bearer ",
                JSONObject().put("title", t).put("head", head.trim()).put("base", base.trim()).put("body", b).toString(),
            )
            Provider.GITLAB -> Call(
                "https://${repo.host}/api/v4/projects/${enc(repo.slug)}/merge_requests",
                "PRIVATE-TOKEN",
                "",
                JSONObject().put("title", t).put("source_branch", head.trim()).put("target_branch", base.trim())
                    .put("description", b).toString(),
            )
            Provider.GITEA -> Call(
                "https://${repo.host}/api/v1/repos/$slugPath/pulls",
                "Authorization",
                "token ",
                JSONObject().put("title", t).put("head", head.trim()).put("base", base.trim()).put("body", b).toString(),
            )
        }
    }

    /** Turns the host's answer into something to show. Never includes the token. */
    fun parseResponse(code: Int, responseBody: String): Result {
        val json = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            null
        }
        if (code in 200..299) {
            val link = json?.optString("html_url", "").orEmpty().ifEmpty { json?.optString("web_url", "").orEmpty() }
            return Result(true, link.ifEmpty { null }, if (link.isEmpty()) "Pull request created." else "Pull request created: $link")
        }
        var detail = ""
        if (json != null) {
            val msg = json.opt("message")
            detail = when (msg) {
                null -> ""
                is org.json.JSONArray -> (0 until msg.length()).joinToString("; ") { msg.optString(it) }
                else -> msg.toString()
            }
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.opt(0)
                val more = if (first is JSONObject) first.optString("message", "") else first?.toString().orEmpty()
                if (more.isNotEmpty()) detail = if (detail.isEmpty()) more else "$detail — $more"
            }
        }
        val hint = when (code) {
            401, 403 -> " Check that the Git Vault token for this host can write pull requests."
            404 -> " The token may not see this repository."
            422, 409 -> " Has the branch been pushed, and is there already a pull request for it?"
            else -> ""
        }
        return Result(false, null, ("The host answered $code. " + detail.take(400)).trim() + hint)
    }

    // ------------------------------------------------------------------- run

    /** Blocking; call it off the main thread. */
    fun create(context: Context, remote: String, title: String, body: String, head: String, base: String): Result {
        inputError(title, head, base)?.let { return Result(false, null, it) }
        val repo = parseRemote(remote) ?: return Result(false, null, "The origin remote is not a URL this app understands.")
        val provider = providerFor(repo.host)
            ?: return Result(false, null, "Creating pull requests on ${repo.host} from the app is not supported.")
        val token = GitVault.tokenFor(context, repo.host)
        if (token.isBlank()) {
            return Result(false, null, "No token for ${repo.host} in the Git Vault. Add one in Settings, or use the browser.")
        }
        val call = buildCall(provider, repo, title, body, head, base)
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(call.url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "OpenMinis/git-panel")
            conn.setRequestProperty(call.authHeader, call.authPrefix + token)
            conn.outputStream.use { it.write(call.body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            parseResponse(code, text.take(200_000))
        } catch (e: Exception) {
            Result(false, null, "Could not reach ${repo.host}: ${e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}
