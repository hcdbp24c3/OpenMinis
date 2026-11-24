package com.openminis.app.ui.chat

/**
 * [T-chat-progress-rail] Pure maths behind the conversation progress rail: the
 * thin strip on the chat's right edge with one tick per user message.
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

    /** Longest preview text, in characters. */
    const val PREVIEW_MAX_CHARS = 90

    data class Marker(val messageId: String, val preview: String)

    /** One line of preview: whitespace runs collapsed, capped with an ellipsis. */
    fun preview(content: String): String {
        val out = StringBuilder()
        var pendingSpace = false
        var i = 0
        // Never walk a huge pasted message end to end for a 90-char preview.
        val scanLimit = minOf(content.length, PREVIEW_MAX_CHARS * 8)
        while (i < scanLimit && out.length <= PREVIEW_MAX_CHARS) {
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
        if (out.length > PREVIEW_MAX_CHARS) {
            return out.substring(0, PREVIEW_MAX_CHARS).trimEnd() + "…"
        }
        if (i < content.length && content.substring(i).isNotBlank()) {
            return out.toString() + "…"
        }
        return out.toString()
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
}
