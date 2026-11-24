package com.openminis.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel

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

/**
 * [T-chat-rail-scrub] First line of the reply that follows a user message, for
 * the rail's preview bubble; empty when the turn has no reply text yet.
 *
 * Only called when the previewed message changes (a press, or a drag crossing
 * onto another message), never per frame and never while streaming.
 *
 * @param nextUserMessageId the next user bubble's id, or null for the last one.
 */
internal fun chatProgressReplyPreview(
    messages: List<ChatMessage>,
    userMessageId: String,
    nextUserMessageId: String?,
): String {
    var user = -1
    var stop = -1
    var i = 0
    while (i < messages.size) {
        val id = messages[i].id
        if (user < 0) {
            if (id == userMessageId) user = i
        } else if (nextUserMessageId != null && id == nextUserMessageId) {
            stop = i
            break
        }
        i++
    }
    val reply: Int = ChatProgressRailModel.replyIndex(user, stop, messages.size) { index ->
        val message = messages[index]
        message.role == "assistant" && message.content.isNotBlank()
    }
    if (reply < 0) return ""
    return ChatProgressRailModel.firstLine(messages[reply].content, ChatProgressRailModel.REPLY_MAX_CHARS)
}

/** Touch strip: everything the rail listens on. Was 16dp. */
private val RailWidth: Dp = 24.dp
/** Height of one tick's row. Was 11dp. */
private val SlotHeight: Dp = 14.dp
private val TickThickness: Dp = 3.dp
private val ActiveTickThickness: Dp = 4.dp
private val TickLength: Dp = 9.dp
private val ActiveTickLength: Dp = 13.dp
private val TickEndGap: Dp = 3.dp
/** The faint track drawn behind the ticks while a finger is on the rail. */
private val TrackWidth: Dp = 19.dp
private val BubbleGap: Dp = 8.dp
private val BubbleMaxWidth: Dp = 248.dp
/** Closest the bubble gets to the top and bottom of the chat area. */
private val BubbleEdgeMargin: Dp = 8.dp

/**
 * [T-chat-progress-rail] One tick per user message on the chat's right edge.
 * The tick for the message the viewport is in is highlighted.
 *
 * [T-chat-rail-scrub] Touch, all inside the rail's own 24dp-wide strip:
 *  - TAP a tick: jump to that message (tapping the message already in view
 *    steps to the next one sharing the tick, as before).
 *  - PRESS AND DRAG up or down: scrub. The message under the finger becomes
 *    the highlighted one and the chat follows it. On a condensed rail the
 *    finger's position inside a tick picks between the messages sharing it.
 *  - PRESS AND HOLD without moving: preview only, no jump on release.
 *  While a finger is down a bubble beside the rail shows the pointed message:
 *  its position ("12/40"), its text and the first line of the reply. It
 *  sits beside that message's tick and snaps from tick to tick; it does not
 *  track the finger, so dragging past either end of the ticks leaves it at
 *  the first or last one. It is gone the moment the finger lifts or the
 *  gesture is taken away.
 *
 * A press that turns into a SIDEWAYS swipe is dropped without consuming it,
 * so the system edge-back gesture and horizontal swipes are untouched.
 *
 * Cost: scrolling re-evaluates one `derivedStateOf` (a couple of map lookups
 * and a scan over an IntArray, no allocation) and, only when the highlighted
 * tick actually changes, redraws this one canvas. A drag recomposes nothing
 * until the pointed message changes: only then does the bubble's content
 * recompose, the bubble move (a layout-phase offset) and the list get asked
 * to scroll. Jumps go
 * through a conflated channel: a fast drag skips the messages the list did
 * not have time to show instead of queueing them up.
 *
 * @param markers the user messages, oldest first.
 * @param rowIndexByKey position of every list row by key (oldest row = 0).
 * @param onJump scrolls to the message with this id.
 * @param replyPreview first line of the reply to a user message, given its id
 *   and the next user message's id (null for the last one).
 */
@Composable
internal fun ChatProgressRail(
    markers: () -> List<ChatProgressRailModel.Marker>,
    rowIndexByKey: () -> Map<String, Int>,
    listState: LazyListState,
    onJump: suspend (String) -> Unit,
    replyPreview: (String, String?) -> String,
    modifier: Modifier = Modifier,
) {
    val list: List<ChatProgressRailModel.Marker> = markers()
    if (list.size < ChatProgressRailModel.MIN_MARKERS) return

    val activeColor: Color = MaterialTheme.colorScheme.primary
    val idleColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    val trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val bubbleColor: Color = MaterialTheme.colorScheme.inverseSurface
    val bubbleText: Color = MaterialTheme.colorScheme.inverseOnSurface
    val haptics = LocalHapticFeedback.current
    val jump = rememberUpdatedState(onJump)
    val reply = rememberUpdatedState(replyPreview)

    // Where this box sits inside the chat area (its parent layout) and how
    // tall that area is. The bubble is centred on a tick, so beside the first
    // or last tick it sticks out of this box; it only has to stay inside the
    // chat area. Read only in the bubble's layout-phase offset. -1: not known.
    val areaHeight = remember { mutableIntStateOf(-1) }
    val boxTopInArea = remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .width(RailWidth)
            .onGloballyPositioned { coordinates ->
                val area = coordinates.parentLayoutCoordinates
                if (area != null && area.isAttached) {
                    areaHeight.intValue = area.size.height
                    boxTopInArea.intValue = area.localPositionOf(coordinates, Offset.Zero).y.toInt()
                } else {
                    areaHeight.intValue = -1
                }
            },
    ) {
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

        // The marker in the bubble (-1: no bubble) and whether a finger is on
        // the rail.
        val shown = remember(list) { mutableIntStateOf(-1) }
        val holding = remember(list) { mutableStateOf(false) }
        val bubbleHeight = remember { mutableIntStateOf(0) }

        // Marker indices to scroll to. Conflated: while the list is still
        // landing one jump, only the newest request is kept.
        val jumps = remember { Channel<Int>(Channel.CONFLATED) }
        LaunchedEffect(list) {
            for (index in jumps) {
                if (index >= 0 && index < list.size) jump.value(list[index].messageId)
            }
        }
        val shownIndex: Int = shown.intValue

        val railHeight: Dp = SlotHeight * slots
        val topGap: Dp = (maxHeight - railHeight) / 2

        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .width(RailWidth)
                .height(railHeight)
                .pointerInput(list, slots) {
                    val slop: Float = viewConfiguration.touchSlop
                    val longPressMs: Long = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down: PointerInputChange = awaitFirstDown(requireUnconsumed = false)
                        val railPx: Float = size.height.toFloat()
                        var target: Int = ChatProgressRailModel.markerAt(
                            down.position.y, railPx, list.size, slots,
                        )
                        if (target < 0 || target >= list.size) return@awaitEachGesture
                        down.consume()
                        shown.intValue = target
                        holding.value = true
                        var scrubbing = false
                        var lifted = false
                        var liftedAt = 0L
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                var change: PointerInputChange? = null
                                for (candidate in event.changes) {
                                    if (candidate.id == down.id) change = candidate
                                }
                                val current: PointerInputChange = change ?: break
                                if (!current.pressed) {
                                    lifted = true
                                    liftedAt = current.uptimeMillis
                                    current.consume()
                                    break
                                }
                                if (!scrubbing) {
                                    val decision: Int = ChatProgressRailModel.dragDecision(
                                        current.position.x - down.position.x,
                                        current.position.y - down.position.y,
                                        slop,
                                    )
                                    // Sideways: not ours. Leave it unconsumed.
                                    if (decision == ChatProgressRailModel.DRAG_NOT_OURS) break
                                    if (decision == ChatProgressRailModel.DRAG_SCRUB) {
                                        scrubbing = true
                                        jumps.trySend(target)
                                    }
                                }
                                if (scrubbing) {
                                    current.consume()
                                    val pointed: Int = ChatProgressRailModel.markerAt(
                                        current.position.y, railPx, list.size, slots,
                                    )
                                    if (pointed != target && pointed >= 0 && pointed < list.size) {
                                        target = pointed
                                        shown.intValue = pointed
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        jumps.trySend(pointed)
                                    }
                                }
                            }
                        } finally {
                            // Finger up, a sideways swipe, or the system
                            // taking the gesture away (edge-back): the bubble
                            // goes at once, with no delay and no fade.
                            holding.value = false
                            shown.intValue = -1
                        }
                        if (lifted && !scrubbing) {
                            // A tap jumps; a long still press was a preview.
                            if (liftedAt - down.uptimeMillis < longPressMs) {
                                val tapped: Int = ChatProgressRailModel.tapTargetAt(
                                    target, list.size, slots, active.value,
                                )
                                if (tapped >= 0 && tapped < list.size) jumps.trySend(tapped)
                            }
                        }
                    }
                },
        ) {
            val held: Boolean = holding.value
            val pointed: Int = shown.intValue
            val marker: Int = if (held && pointed >= 0) pointed else active.value
            val activeSlot: Int = if (marker < 0) -1
            else ChatProgressRailModel.slotOf(marker, list.size, slots)
            val slotPx: Float = size.height / slots
            val endGap: Float = TickEndGap.toPx()
            if (held) {
                val trackWidth: Float = TrackWidth.toPx()
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(size.width - trackWidth, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f),
                )
            }
            var s = 0
            while (s < slots) {
                val isActive = s == activeSlot
                val len: Float = (if (isActive) ActiveTickLength else TickLength).toPx()
                val thick: Float = (if (isActive) ActiveTickThickness else TickThickness).toPx()
                drawRoundRect(
                    color = if (isActive) activeColor else idleColor,
                    topLeft = Offset(size.width - endGap - len, slotPx * s + (slotPx - thick) / 2f),
                    size = Size(len, thick),
                    cornerRadius = CornerRadius(thick / 2f, thick / 2f),
                )
                s++
            }
        }

        if (shownIndex >= 0 && shownIndex < list.size) {
            val pointedMarker: ChatProgressRailModel.Marker = list[shownIndex]
            val nextId: String? = if (shownIndex + 1 < list.size) list[shownIndex + 1].messageId else null
            val replyText: String = remember(pointedMarker.messageId, nextId) {
                reply.value(pointedMarker.messageId, nextId)
            }
            val density = LocalDensity.current
            val railTopPx: Int = with(density) { topGap.roundToPx() }
            val railHeightPx: Int = with(density) { railHeight.roundToPx() }
            // The tick the bubble sits beside. On a condensed rail several
            // messages share it: the text changes, the bubble stays put.
            val shownSlot: Int = ChatProgressRailModel.slotOf(shownIndex, list.size, slots)
            val leftPx: Int = with(density) { (RailWidth + BubbleGap).roundToPx() }
            val boxHeightPx: Int = constraints.maxHeight
            val marginPx: Int = with(density) { BubbleEdgeMargin.roundToPx() }
            val shape = RoundedCornerShape(12.dp)
            // Laid out past the rail's own 24dp (unbounded) and placed with a
            // layout-phase offset: centred on the pointed message's tick, so
            // it snaps from tick to tick and never follows the raw finger.
            // Only nudged to stay inside the chat area, a margin off its edges.
            // Plain Column, not a Surface, so it never swallows touches.
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .wrapContentSize(align = Alignment.TopEnd, unbounded = true)
                    .offset {
                        IntOffset(
                            x = -leftPx,
                            y = run {
                                val area: Int = areaHeight.intValue
                                val boxTop: Int = boxTopInArea.intValue
                                // Until the chat area is known: this box.
                                val minTop: Int = if (area < 0) 0 else marginPx - boxTop
                                val maxBottom: Int = if (area < 0) boxHeightPx else area - boxTop - marginPx
                                ChatProgressRailModel.bubbleTopAtSlot(
                                    shownSlot,
                                    slots,
                                    railTopPx,
                                    railHeightPx,
                                    bubbleHeight.intValue,
                                    minTop,
                                    maxBottom,
                                )
                            },
                        )
                    }
                    .onSizeChanged { bubbleHeight.intValue = it.height }
                    .widthIn(max = BubbleMaxWidth)
                    .shadow(6.dp, shape)
                    .background(bubbleColor, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = ChatProgressRailModel.positionLabel(shownIndex, list.size),
                    color = bubbleText.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = pointedMarker.preview.ifEmpty { "(no text)" },
                    color = bubbleText,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (replyText.isNotEmpty()) {
                    Text(
                        text = "\u21B3 " + replyText,
                        color = bubbleText.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
