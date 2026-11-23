package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.tools.TodoItem
import com.openminis.app.tools.TodoList
import com.openminis.app.tools.TodoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [T-coding-m2-todo] Teal: progress, set apart from the amber of scheduled tasks. */
internal val TodoAccent = Color(0xFF30B0C7)

/**
 * [T-coding-m2-todo] The agent's task list, pinned above the composer.
 *
 * Collapsed it is one line — the step in hand and "2/5" — so it costs the chat
 * a single row. A tap opens the whole list; "Clear" there removes it. Nothing
 * is drawn while the conversation has no list.
 *
 * [sessionId] is a lambda, not a value: a new chat gets its real id only once
 * the first message is sent, and that id is not Compose state. It is read here
 * on every recomposition, and the store changing is what recomposes this.
 *
 * Wording is literal English for now, like the Shell approval rows in
 * Settings, until it settles.
 */
@Composable
internal fun SessionTodoCard(
    sessionId: () -> String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val all by TodoStore.lists.collectAsState()
    val id = sessionId()
    LaunchedEffect(id) {
        if (id.isNotEmpty()) {
            withContext(Dispatchers.IO) { TodoStore.ensureLoaded(context, id) }
        }
    }
    val items: List<TodoItem> = all[id].orEmpty()
    if (items.isEmpty()) return

    var expanded by remember(id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val done = TodoList.doneCount(items)
    val current = TodoList.headline(items)
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(shape)
            .background(TodoAccent.copy(alpha = 0.07f))
            .border(0.8.dp, TodoAccent.copy(alpha = 0.35f), shape)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.content ?: "All tasks done",
                modifier = Modifier.weight(1f),
                color = onSurface.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$done/${items.size}",
                color = TodoAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (item in items) {
                    val isDone = item.status == TodoList.DONE
                    val isActive = item.status == TodoList.IN_PROGRESS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (isDone) "✓" else if (isActive) "▸" else "○",
                            modifier = Modifier.width(18.dp),
                            color = if (isDone || isActive) TodoAccent else onSurface.copy(alpha = 0.4f),
                            fontSize = 13.sp,
                        )
                        Text(
                            text = item.content,
                            modifier = Modifier.weight(1f),
                            color = onSurface.copy(alpha = if (isDone) 0.45f else 0.85f),
                            fontSize = 13.sp,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                            textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                        )
                    }
                }
            }
            Text(
                text = "Clear",
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { TodoStore.set(context, id, emptyList(), persist = true) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
    }
}
