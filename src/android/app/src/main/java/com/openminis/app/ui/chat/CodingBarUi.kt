package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openminis.app.agent.PlanMode
import com.openminis.app.agent.ShadowGit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val PlanAccent = Color(0xFF8E7CF0)
internal val ChangesAccent = Color(0xFF34C759)
private val DiffRemoved = Color(0xFFFF453A)

/**
 * [T-coding-m3] The coding rows above the composer: the plan-mode chip and the
 * turn's "changes" card. Each draws nothing when it has nothing to say, so a
 * conversation that is not coding sees no difference.
 *
 * [sessionId] is a lambda for the same reason as in [SessionTodoCard].
 */
@Composable
internal fun SessionCodingBar(
    sessionId: () -> String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val planVersion by PlanMode.version.collectAsState()
    val turns by ShadowGit.turns.collectAsState()
    val id = sessionId()

    val planOn = remember(id, planVersion) { PlanMode.isOn(context, id) }
    val turn = turns[id]
    val hasSnapshot = turn != null && !turn.pending && turn.dir != null
    if (!planOn && !hasSnapshot) return

    var showChanges by remember(id) { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (planOn) {
            CodingChip(
                text = "Plan mode · read-only · tap to exit",
                accent = PlanAccent,
                onClick = { PlanMode.set(context, id, false) },
            )
        }
        if (hasSnapshot) {
            CodingChip(
                text = "Review changes",
                accent = ChangesAccent,
                onClick = { showChanges = true },
            )
        }
    }

    if (showChanges) {
        TurnChangesDialog(sessionId = id, onDismiss = { showChanges = false })
    }
}

@Composable
private fun CodingChip(text: String, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = text,
        modifier = Modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.08f))
            .border(0.8.dp, accent.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * [T-coding-m3-shadow] What the turn changed: files with line counts, then the
 * diff. "Revert all" puts the project back as it was before the turn's first
 * change, and asks twice.
 */
@Composable
private fun TurnChangesDialog(sessionId: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var changes by remember { mutableStateOf<ShadowGit.Changes?>(null) }
    var reload by remember { mutableStateOf(0) }
    var confirmRevert by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId, reload) {
        loading = true
        changes = withContext(Dispatchers.IO) { ShadowGit.changes(sessionId) }
        loading = false
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val current = changes
                Text(
                    text = "Changes this turn",
                    color = onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        loading -> "Reading…"
                        current == null -> "Could not read the changes for this turn."
                        else -> ShadowGit.summary(current)
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    color = onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
                val message = note
                if (message != null) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = ChangesAccent,
                        fontSize = 12.sp,
                    )
                }

                val lines: List<String> = remember(current) {
                    if (current == null) {
                        emptyList()
                    } else {
                        val head = current.files.map { f ->
                            if (f.binary) "  (binary)  ${f.path}" else "  +${f.added} -${f.removed}  ${f.path}"
                        }
                        val body = if (current.patch.isBlank()) emptyList() else listOf("") + current.patch.lines()
                        val tail = if (current.truncated) listOf("", "[diff truncated]") else emptyList()
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
                            line.startsWith("+") -> ChangesAccent
                            line.startsWith("-") -> DiffRemoved
                            line.startsWith("@@") || line.startsWith("diff ") -> PlanAccent
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canRevert = !loading && !reverting && current != null && current.files.isNotEmpty()
                    if (canRevert) {
                        CodingChip(
                            text = if (confirmRevert) "Tap again to revert everything" else "Revert all",
                            accent = DiffRemoved,
                            onClick = {
                                if (!confirmRevert) {
                                    confirmRevert = true
                                } else {
                                    confirmRevert = false
                                    reverting = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { ShadowGit.revert(sessionId) }
                                        note = if (ok) "Reverted to the start of the turn." else "Revert failed; nothing was confirmed changed."
                                        reverting = false
                                        reload += 1
                                    }
                                }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    CodingChip(text = "Close", accent = onSurface.copy(alpha = 0.7f), onClick = onDismiss)
                }
            }
        }
    }
}
