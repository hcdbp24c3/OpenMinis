package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openminis.app.agent.GitPanel
import com.openminis.app.agent.GitPullRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val GitAccent = Color(0xFFF05033)
private val GitAdded = Color(0xFF34C759)
private val GitRemoved = Color(0xFFFF453A)
private val GitHunk = Color(0xFF8E7CF0)

@Composable
private fun GitButton(text: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val tint = if (enabled) accent else accent.copy(alpha = 0.35f)
    Text(
        text = text,
        modifier = Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.08f))
            .border(0.8.dp, tint.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = tint,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * [T-coding-m4-git] The project's own repository: branch, changed files, diff,
 * and the things worth doing from a phone — stage files, commit, switch or
 * create a branch, pull, push, and open a pull request. Every action is a tap;
 * nothing here runs on its own.
 */
@Composable
internal fun GitPanelDialog(sessionId: String, dir: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<GitPanel.Status?>(null) }
    var reload by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var outputOk by remember { mutableStateOf(true) }
    val context = LocalContext.current
    // Which of the two extra forms is open: "" (diff), "branch" or "pr".
    var mode by remember { mutableStateOf("") }
    var newBranch by remember { mutableStateOf("") }
    var prTitle by remember { mutableStateOf("") }
    var prBody by remember { mutableStateOf("") }
    var prBase by remember { mutableStateOf("") }
    var prLink by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId, dir, reload) {
        loading = true
        status = withContext(Dispatchers.IO) { GitPanel.status(sessionId, dir) }
        loading = false
    }

    fun act(block: suspend () -> GitPanel.Outcome, clearMessage: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { block() }
            output = outcome.output.ifBlank { if (outcome.ok) "Done." else "Failed." }.takeLast(4_000)
            outputOk = outcome.ok
            if (outcome.ok && clearMessage) message = ""
            busy = false
            reload += 1
        }
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val current = status
                val isRepo = current != null && current.isRepo
                Text(
                    text = if (isRepo && current != null && current.branch.isNotEmpty()) "Git · ${current.branch}" else "Git",
                    color = onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        loading -> "Reading $dir…"
                        current == null -> "Could not run git in $dir."
                        !current.isRepo -> "$dir is not a git repository."
                        current.files.isEmpty() -> "Working tree clean · $dir"
                        else -> "${current.files.size} changed · $dir"
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    color = onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val files = if (isRepo && current != null) current.files else emptyList()
                val lines: List<String> = remember(current) {
                    if (current == null || !current.isRepo) {
                        emptyList()
                    } else {
                        val body = if (current.diff.isBlank()) emptyList() else listOf("") + current.diff.lines()
                        val tail = if (current.diffTruncated) listOf("", "[diff truncated]") else emptyList()
                        body + tail
                    }
                }
                if (mode == "branch" && isRepo && current != null) {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(current.branches) { name ->
                            val here = name == current.branch
                            Text(
                                text = (if (here) "● " else "○ ") + name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy && !here) {
                                        act({ GitPanel.switchTo(sessionId, dir, name, create = false) })
                                        mode = ""
                                    }
                                    .padding(vertical = 8.dp),
                                color = if (here) GitAccent else onSurface.copy(alpha = 0.85f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newBranch,
                        onValueChange = { newBranch = it.take(GitPanel.BRANCH_MAX_CHARS) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("New branch name", fontSize = 13.sp) },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GitButton("Create and switch", GitAdded, !busy && GitPanel.branchNameError(newBranch.trim()) == null) {
                            val name = newBranch.trim()
                            act({ GitPanel.switchTo(sessionId, dir, name, create = true) })
                            newBranch = ""
                            mode = ""
                        }
                        GitButton("Back", onSurface.copy(alpha = 0.7f), true) { mode = "" }
                    }
                } else if (mode == "pr" && isRepo && current != null) {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Text(
                            text = "From ${current.branch} · push the branch first",
                            color = onSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                        OutlinedTextField(
                            value = prTitle,
                            onValueChange = { prTitle = it.take(GitPullRequest.TITLE_MAX_CHARS) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            placeholder = { Text("Title", fontSize = 13.sp) },
                            singleLine = true,
                            enabled = !busy,
                        )
                        OutlinedTextField(
                            value = prBase,
                            onValueChange = { prBase = it.take(GitPanel.BRANCH_MAX_CHARS) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            placeholder = { Text("Merge into (branch)", fontSize = 13.sp) },
                            singleLine = true,
                            enabled = !busy,
                        )
                        OutlinedTextField(
                            value = prBody,
                            onValueChange = { prBody = it.take(GitPullRequest.BODY_MAX_CHARS) },
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                            placeholder = { Text("Description (optional)", fontSize = 13.sp) },
                            enabled = !busy,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GitButton(
                            "Create pull request",
                            GitAdded,
                            !busy && GitPullRequest.inputError(prTitle, current.branch, prBase) == null,
                        ) {
                            if (!busy) {
                                busy = true
                                val remote = current.remote
                                val head = current.branch
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        GitPullRequest.create(context, remote, prTitle, prBody, head, prBase)
                                    }
                                    output = result.message
                                    outputOk = result.ok
                                    prLink = result.url
                                    if (result.ok) {
                                        prTitle = ""
                                        prBody = ""
                                        mode = ""
                                    }
                                    busy = false
                                }
                            }
                        }
                        GitButton("Back", onSurface.copy(alpha = 0.7f), true) { mode = "" }
                    }
                } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    items(files) { file ->
                        Text(
                            text = (if (file.staged) "[x] " else "[ ] ") + file.code + " " + file.path,
                            modifier = Modifier
                                .clickable(enabled = !busy) {
                                    act({ GitPanel.setStaged(sessionId, dir, file.path, !file.staged) })
                                }
                                .padding(vertical = 5.dp),
                            color = if (file.staged) GitAdded else onSurface.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                    items(lines) { line ->
                        val color = when {
                            line.startsWith("+++") || line.startsWith("---") -> onSurface.copy(alpha = 0.6f)
                            line.startsWith("+") -> GitAdded
                            line.startsWith("-") -> GitRemoved
                            line.startsWith("@@") || line.startsWith("diff ") -> GitHunk
                            else -> onSurface.copy(alpha = 0.85f)
                        }
                        Text(
                            text = line,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
                }

                val shown = output
                if (shown != null) {
                    Text(
                        text = shown,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = if (outputOk) onSurface.copy(alpha = 0.75f) else GitRemoved,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val link = prLink
                if (link != null) {
                    GitButton("Open pull request", GitAdded, true) {
                        try {
                            uriHandler.openUri(link)
                        } catch (_: Exception) {
                        }
                    }
                }

                if (isRepo && current != null && mode == "") {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it.take(GitPanel.MESSAGE_MAX_CHARS) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        placeholder = { Text("Commit message", fontSize = 13.sp) },
                        maxLines = 3,
                        enabled = !busy,
                    )
                    val ready = !busy && !loading
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Nothing staged: commit everything. Something staged: commit just that.
                        val all = !current.anyStaged
                        GitButton(
                            text = if (all) "Commit all" else "Commit staged",
                            accent = GitAdded,
                            enabled = ready && message.isNotBlank() && current.files.isNotEmpty(),
                            onClick = { act({ GitPanel.commit(sessionId, dir, message, all) }, clearMessage = true) },
                        )
                        GitButton("Branch", GitHunk, ready) { mode = "branch" }
                        GitButton("Pull", GitHunk, ready) { act({ GitPanel.pull(sessionId, dir) }) }
                        GitButton("Push", GitAccent, ready) { act({ GitPanel.push(sessionId, dir) }) }
                        val repo = GitPullRequest.parseRemote(current.remote)
                        val inApp = repo != null && GitPullRequest.providerFor(repo.host) != null
                        val prUrl = GitPanel.newPullRequestUrl(current.remote, current.branch)
                        if (inApp && current.branch.isNotEmpty()) {
                            GitButton("Pull request", onSurface.copy(alpha = 0.8f), ready) {
                                if (prBase.isBlank()) prBase = current.base
                                prLink = null
                                mode = "pr"
                            }
                        }
                        if (prUrl != null) {
                            GitButton("PR in browser", onSurface.copy(alpha = 0.8f), ready) {
                                try {
                                    uriHandler.openUri(prUrl)
                                } catch (_: Exception) {
                                    output = prUrl
                                    outputOk = true
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                ) {
                    GitButton(if (busy) "Working…" else "Refresh", onSurface.copy(alpha = 0.7f), !busy) { reload += 1 }
                    GitButton("Close", onSurface.copy(alpha = 0.7f), true, onDismiss)
                }
            }
        }
    }
}
