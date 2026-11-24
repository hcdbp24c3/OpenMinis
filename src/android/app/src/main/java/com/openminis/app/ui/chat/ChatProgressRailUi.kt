package com.openminis.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

/**
 * [T-chat-progress-rail] Builds the rail's markers from the message list and
 * hands back the SAME list instance while nothing about the user messages
 * changed.
 *
 * The message list is replaced on every streamed token. Deciding "is this a
 * user bubble" parses the message text, so the answer is cached per message id
 * (re-checked only when the text length changes) and a streaming reply costs
 * one hash lookup per message instead of a parse.
 */
internal class ChatProgressMarkerCache {
    private class Entry(val length: Int, val marker: ChatProgressRailModel.Marker?)

    private val entries = HashMap<String, Entry>()
    private var last: List<ChatProgressRailModel.Marker> = emptyList()

    fun markers(messages: List<ChatMessage>): List<ChatProgressRailModel.Marker> {
        val out = ArrayList<ChatProgressRailModel.Marker>()
        for (message in messages) {
            if (message.role != "user") continue
            val cached = entries[message.id]
            val entry: Entry = if (cached != null && cached.length == message.content.length) {
                cached
            } else {
                val fresh = Entry(
                    message.content.length,
                    if (message.rendersAsUserBubble()) {
                        ChatProgressRailModel.Marker(
                            message.id,
                            ChatProgressRailModel.preview(message.content),
                        )
                    } else {
                        null
                    },
                )
                entries[message.id] = fresh
                fresh
            }
            val marker = entry.marker
            if (marker != null) out.add(marker)
        }
        if (entries.size > messages.size + 64) {
            val live = HashSet<String>(messages.size * 2)
            for (message in messages) live.add(message.id)
            entries.keys.retainAll(live)
        }
        if (out == last) return last
        last = out
        return out
    }
}

private val RailWidth: Dp = 16.dp
private val SlotHeight: Dp = 11.dp
private const val PREVIEW_MS = 1_800L

/**
 * [T-chat-progress-rail] One tick per user message on the chat's right edge.
 * The tick for the message the viewport is in is highlighted; a tap jumps to
 * that message, a long press only shows its first line.
 *
 * Cost: scrolling re-evaluates one `derivedStateOf` (a couple of map lookups
 * and a scan over an IntArray, no allocation) and, only when the highlighted
 * tick actually changes, redraws this one canvas. The chat screen and the
 * message rows are not recomposed by it.
 *
 * Touch: the rail only listens for taps and long presses inside its own
 * 16dp-wide strip. It does not handle drags, so the system edge-back swipe and
 * horizontal swipes are untouched; a vertical drag that STARTS on the strip
 * does not scroll the list.
 *
 * @param markers the user messages, oldest first.
 * @param rowIndexByKey position of every list row by key (oldest row = 0).
 * @param onJump called with the message id to scroll to.
 */
@Composable
internal fun ChatProgressRail(
    markers: () -> List<ChatProgressRailModel.Marker>,
    rowIndexByKey: () -> Map<String, Int>,
    listState: LazyListState,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list: List<ChatProgressRailModel.Marker> = markers()
    if (list.size < ChatProgressRailModel.MIN_MARKERS) return

    val activeColor: Color = MaterialTheme.colorScheme.primary
    val idleColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)

    BoxWithConstraints(modifier = modifier.width(RailWidth)) {
        val room: Int = (maxHeight.value / SlotHeight.value).toInt()
        val slots: Int = ChatProgressRailModel.slotCount(list.size, room)
        if (slots <= 0) return@BoxWithConstraints

        // Row position of each marker. Recomputed when the rows are republished
        // (not on scroll).
        val positions: State<IntArray> = remember(list) {
            derivedStateOf {
                val map = rowIndexByKey()
                IntArray(list.size) { i ->
                    map["user:" + list[i].messageId] ?: ChatProgressRailModel.UNKNOWN_POS
                }
            }
        }
        // The highlighted marker. Re-evaluated while scrolling; readers are
        // only notified when the value changes.
        val active: State<Int> = remember(list, listState) {
            derivedStateOf {
                val map = rowIndexByKey()
                val visible = listState.layoutInfo.visibleItemsInfo
                // reverseLayout: the highest visible index is the row at the
                // top of the screen. Walk down from it to the first row the
                // map knows (leading banner rows are not in the map).
                var top = -1
                var i = visible.size - 1
                while (i >= 0 && top < 0) {
                    val key = visible[i].key
                    if (key is String) {
                        val pos = map[key]
                        if (pos != null) top = pos
                    }
                    i--
                }
                if (top < 0) -1 else ChatProgressRailModel.activeMarker(positions.value, top)
            }
        }

        var previewIndex by remember(list) { mutableIntStateOf(-1) }
        LaunchedEffect(previewIndex) {
            if (previewIndex >= 0) {
                delay(PREVIEW_MS)
                previewIndex = -1
            }
        }

        val railHeight: Dp = SlotHeight * slots
        val topGap: Dp = (maxHeight - railHeight) / 2

        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .width(RailWidth)
                .height(railHeight)
                .pointerInput(list, slots) {
                    detectTapGestures(
                        onTap = { offset ->
                            val slot = ChatProgressRailModel.slotAt(offset.y, size.height.toFloat(), slots)
                            val target = ChatProgressRailModel.tapTarget(slot, list.size, slots, active.value)
                            if (target in list.indices) {
                                previewIndex = target
                                onJump(list[target].messageId)
                            }
                        },
                        onLongPress = { offset ->
                            val slot = ChatProgressRailModel.slotAt(offset.y, size.height.toFloat(), slots)
                            val target = ChatProgressRailModel.slotStart(slot, list.size, slots)
                            if (target in list.indices) previewIndex = target
                        },
                    )
                },
        ) {
            val activeSlot: Int = if (active.value < 0) -1
            else ChatProgressRailModel.slotOf(active.value, list.size, slots)
            val slotPx: Float = size.height / slots
            val thick: Float = 2.dp.toPx()
            val idleLen: Float = 6.dp.toPx()
            val activeLen: Float = 10.dp.toPx()
            val endGap: Float = 3.dp.toPx()
            val radius = CornerRadius(thick / 2f, thick / 2f)
            var s = 0
            while (s < slots) {
                val isActive = s == activeSlot
                val len = if (isActive) activeLen else idleLen
                drawRoundRect(
                    color = if (isActive) activeColor else idleColor,
                    topLeft = Offset(size.width - endGap - len, slotPx * s + (slotPx - thick) / 2f),
                    size = Size(len, thick),
                    cornerRadius = radius,
                )
                s++
            }
        }

        val shown: Int = previewIndex
        if (shown >= 0 && shown < list.size && list[shown].preview.isNotEmpty()) {
            val density = LocalDensity.current
            val slot: Int = ChatProgressRailModel.slotOf(shown, list.size, slots)
            val offset: IntOffset = with(density) {
                IntOffset(
                    x = -(RailWidth + 6.dp).roundToPx(),
                    y = (topGap + SlotHeight * slot - 10.dp).roundToPx().coerceAtLeast(0),
                )
            }
            Popup(alignment = Alignment.TopEnd, offset = offset) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = list[shown].preview,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
