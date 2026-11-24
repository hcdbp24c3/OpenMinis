package com.openminis.app.ui.chat

/**
 * [T-chat-progress-rail] Pure maths behind the conversation progress rail: the
 * strip on the chat's right edge with one tick per user message, which can be
 * tapped or dragged along to scrub through the conversation.
 *
 * Nothing here touches Compose or Android, so the mapping from messages to
 * ticks, from the viewport to the highlighted tick, and the condensing of a
 * long chat onto a short rail are all unit-tested on the JVM.
 *
 * Vocabulary: a MARKER is one user message (oldest first). A SLOT is one tick
 * drawn on the rail. Short chats have one slot per marker; a chat with more
 * markers than the rail has room for shares slots, each covering a run of
 * consecutive markers.
 */
internal object ChatProgressRailModel {

    /** Below this many user messages the rail is not worth showing. */
    const val MIN_MARKERS = 3

    /** Most ticks ever drawn, however tall the screen. */
    const val MAX_SLOTS = 28

    /** Longest preview text, in characters (about three lines of the bubble). */
    const val PREVIEW_MAX_CHARS = 140

    /** Longest first-line-of-the-reply text shown under a preview. */
    const val REPLY_MAX_CHARS = 60

    data class Marker(val messageId: String, val preview: String)

    /** One line of preview: whitespace runs collapsed, capped with an ellipsis. */
    fun preview(content: String): String = preview(content, PREVIEW_MAX_CHARS)

    /** [preview] with an explicit cap of [maxChars] characters. */
    fun preview(content: String, maxChars: Int): String {
        val cap = maxChars.coerceAtLeast(1)
        val out = StringBuilder()
        var pendingSpace = false
        var i = 0
        // Never walk a huge pasted message end to end for a short preview.
        val scanLimit = minOf(content.length, cap * 8)
        while (i < scanLimit && out.length <= cap) {
            val c = content[i]
            if (c.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
            i++
        }
        if (out.length > cap) {
            return out.substring(0, cap).trimEnd() + "…"
        }
        if (i < content.length && content.substring(i).isNotBlank()) {
            return out.toString() + "…"
        }
        return out.toString()
    }

    /**
     * The first line of [content] as a preview of at most [maxChars]
     * characters: leading blank lines skipped, cut at the first line break.
     */
    fun firstLine(content: String, maxChars: Int): String {
        var start = 0
        while (start < content.length && content[start].isWhitespace()) start++
        if (start >= content.length) return ""
        val scanEnd = minOf(content.length, start + maxChars.coerceAtLeast(1) * 8)
        var end = start
        while (end < scanEnd && content[end] != '\n' && content[end] != '\r') end++
        val line = preview(content.substring(start, end), maxChars)
        // Cut by the scan limit, not by a line break: say so.
        if (end == scanEnd && end < content.length && !line.endsWith("…")) return line + "…"
        return line
    }

    /**
     * How many ticks to draw: none for a short chat or a rail with no room,
     * one per marker when they fit, otherwise as many as fit.
     */
    fun slotCount(markerCount: Int, roomForSlots: Int): Int {
        if (markerCount < MIN_MARKERS) return 0
        val room = minOf(roomForSlots, MAX_SLOTS)
        if (room < MIN_MARKERS) return 0
        return minOf(markerCount, room)
    }

    /** First marker a slot covers. `slotStart(slots)` equals [markerCount]. */
    fun slotStart(slot: Int, markerCount: Int, slots: Int): Int {
        if (slots <= 0) return 0
        return ((slot.toLong() * markerCount.toLong()) / slots.toLong()).toInt()
    }

    /** The slot that covers [markerIndex]. */
    fun slotOf(markerIndex: Int, markerCount: Int, slots: Int): Int {
        if (slots <= 0 || markerCount <= 0) return 0
        val idx = markerIndex.coerceIn(0, markerCount - 1)
        var s = ((idx.toLong() * slots.toLong()) / markerCount.toLong()).toInt().coerceIn(0, slots - 1)
        while (s < slots - 1 && slotStart(s + 1, markerCount, slots) <= idx) s++
        while (s > 0 && slotStart(s, markerCount, slots) > idx) s--
        return s
    }

    /**
     * Which marker a tap on [slot] jumps to.
     *
     * One marker per slot: that marker. A shared slot jumps to its first
     * marker, and tapping it again while the viewport is already inside the
     * slot steps to the next one, so every message stays reachable.
     */
    fun tapTarget(slot: Int, markerCount: Int, slots: Int, activeMarker: Int): Int {
        if (markerCount <= 0 || slots <= 0) return -1
        val s = slot.coerceIn(0, slots - 1)
        val first = slotStart(s, markerCount, slots)
        val last = (slotStart(s + 1, markerCount, slots) - 1).coerceAtLeast(first)
        if (activeMarker in first until last) return activeMarker + 1
        return first.coerceIn(0, markerCount - 1)
    }

    /**
     * The marker the viewport is "in": the newest user message at or before
     * the row at the top of the screen.
     *
     * [markerRowPos] holds each marker's row position (oldest row = 0), oldest
     * marker first; a marker whose row is not known yet is [UNKNOWN_POS].
     * [topRowPos] is the position of the row at the top of the viewport.
     * Returns -1 when there are no markers, and the first marker when the
     * viewport sits above every one of them.
     */
    fun activeMarker(markerRowPos: IntArray, topRowPos: Int): Int {
        if (markerRowPos.isEmpty()) return -1
        var i = markerRowPos.size - 1
        while (i >= 0) {
            val p = markerRowPos[i]
            if (p != UNKNOWN_POS && p <= topRowPos) return i
            i--
        }
        return 0
    }

    const val UNKNOWN_POS = Int.MAX_VALUE

    /** The slot under a touch at [y] on a rail [height] tall. */
    fun slotAt(y: Float, height: Float, slots: Int): Int {
        if (slots <= 0 || height <= 0f) return 0
        return ((y / height) * slots).toInt().coerceIn(0, slots - 1)
    }

    // ── [T-chat-rail-scrub] Press, drag and preview ─────────────────────────

    /**
     * The single marker under a finger at [y] on a rail [height] tall, or -1
     * when there is nothing to point at.
     *
     * One marker per slot: the slot's marker. On a condensed rail the finger's
     * position INSIDE the slot picks one of the markers sharing it, so a drag
     * walks through every message and not only through the ticks. Always a
     * marker of the slot [slotAt] reports for the same [y], so the highlighted
     * tick and the previewed message never disagree. A touch above or below
     * the rail clamps to the first or last marker.
     */
    fun markerAt(y: Float, height: Float, markerCount: Int, slots: Int): Int {
        if (markerCount <= 0) return -1
        if (slots <= 0 || height <= 0f) return 0
        val slot = slotAt(y, height, slots)
        val first = slotStart(slot, markerCount, slots).coerceIn(0, markerCount - 1)
        val end = slotStart(slot + 1, markerCount, slots).coerceIn(first + 1, markerCount)
        val shared = end - first
        if (shared <= 1) return first
        val slotPx = height / slots
        val within = ((y - slotPx * slot) / slotPx).coerceIn(0f, 1f)
        return (first + (within * shared).toInt()).coerceIn(first, end - 1)
    }

    /**
     * Which marker a TAP that pressed on [pressed] jumps to.
     *
     * Normally the pressed marker itself. Tapping the marker the viewport is
     * already on keeps the old stepping behaviour ([tapTarget]): on a shared
     * tick it moves to the next message of that tick, so repeated taps on one
     * spot still walk through all of them.
     */
    fun tapTargetAt(pressed: Int, markerCount: Int, slots: Int, activeMarker: Int): Int {
        if (markerCount <= 0 || slots <= 0) return -1
        val p = pressed.coerceIn(0, markerCount - 1)
        if (p != activeMarker) return p
        return tapTarget(slotOf(p, markerCount, slots), markerCount, slots, activeMarker)
    }

    const val DRAG_UNDECIDED = 0
    /** The finger is scrubbing along the rail. */
    const val DRAG_SCRUB = 1
    /** A sideways swipe (edge-back, drawer): not ours, let it through. */
    const val DRAG_NOT_OURS = 2

    /**
     * Decides what a finger that moved ([dx], [dy]) since it went down is
     * doing, given the platform touch [slop]. Sideways movement wins ties, so
     * the rail never claims a horizontal swipe.
     */
    fun dragDecision(dx: Float, dy: Float, slop: Float): Int {
        val ax = if (dx < 0f) -dx else dx
        val ay = if (dy < 0f) -dy else dy
        if (ax < slop && ay < slop) return DRAG_UNDECIDED
        return if (ay > ax) DRAG_SCRUB else DRAG_NOT_OURS
    }

    /** "12/40" for the marker at [index] (0-based) out of [count]. */
    fun positionLabel(index: Int, count: Int): String {
        if (count <= 0) return ""
        return (index.coerceIn(0, count - 1) + 1).toString() + "/" + count.toString()
    }

    /**
     * Top edge of the preview bubble, in px inside a box [boxHeight] tall.
     *
     * The bubble sits mostly ABOVE the finger (its bottom edge [below] px
     * under it) so the thumb does not cover the text, and is kept inside the
     * box at both ends.
     */
    fun bubbleTop(fingerY: Float, bubbleHeight: Int, boxHeight: Int, below: Int): Int {
        val wanted = (fingerY + below - bubbleHeight).toInt()
        val max = boxHeight - bubbleHeight
        if (max <= 0) return 0
        return wanted.coerceIn(0, max)
    }

    /**
     * Index of the reply to show under a user message's preview: the first
     * assistant message with text after [userIndex] and before [stopIndex]
     * (the next user bubble, or [count] for the last one). -1 when the turn
     * has no reply text yet.
     */
    fun replyIndex(
        userIndex: Int,
        stopIndex: Int,
        count: Int,
        isAssistantWithText: (Int) -> Boolean,
    ): Int {
        if (userIndex < 0) return -1
        val stop = minOf(if (stopIndex < 0) count else stopIndex, count)
        var i = userIndex + 1
        while (i < stop) {
            if (isAssistantWithText(i)) return i
            i++
        }
        return -1
    }
}
