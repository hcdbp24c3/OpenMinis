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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openminis.app.agent.GitPanel
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
 * and the four things worth doing from a phone — commit everything, pull, push,
 * and open the pull-request page. Every action is a tap; nothing here runs on
 * its own.
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

                val lines: List<String> = remember(current) {
                    if (current == null || !current.isRepo) {
                        emptyList()
                    } else {
                        val head = current.files.map { "${it.code} ${it.path}" }
                        val body = if (current.diff.isBlank()) emptyList() else listOf("") + current.diff.lines()
                        val tail = if (current.diffTruncated) listOf("", "[diff truncated]") else emptyList()
                        head + body + tail
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
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

                if (isRepo && current != null) {
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
                        GitButton(
                            text = "Commit all",
                            accent = GitAdded,
                            enabled = ready && message.isNotBlank() && current.files.isNotEmpty(),
                            onClick = { act({ GitPanel.commit(sessionId, dir, message) }, clearMessage = true) },
                        )
                        GitButton("Pull", GitHunk, ready) { act({ GitPanel.pull(sessionId, dir) }) }
                        GitButton("Push", GitAccent, ready) { act({ GitPanel.push(sessionId, dir) }) }
                        val prUrl = GitPanel.newPullRequestUrl(current.remote, current.branch)
                        if (prUrl != null) {
                            GitButton("Open PR page", onSurface.copy(alpha = 0.8f), ready) {
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
