package com.openminis.app.tools

/**
 * [T-web-m5] Reading a long page in pieces instead of losing its tail.
 *
 * `web_fetch` used to cut at `max_chars` and say so; the rest of the page was
 * unreachable without asking for a bigger cut from the top again. A window is
 * now `[offset, offset + maxChars)` over the CONVERTED text, and the trailer
 * names the exact offset to continue from.
 *
 * Offsets are UTF-16 code units (Kotlin `String.length`). Two adjustments keep
 * a window readable:
 *  - a boundary never splits a surrogate pair (an emoji stays whole);
 *  - when a line break sits in the last fifth of the window, the window ends
 *    there, so a paragraph, list item or code line is not cut mid-way.
 * `nextOffset` always follows the adjusted end, so concatenating every window
 * reproduces the text exactly.
 */
object WebPaging {

    data class Window(
        val content: String,
        /** First code unit included (after clamping). */
        val start: Int,
        /** One past the last code unit included. */
        val end: Int,
        val total: Int,
    ) {
        val hasMore: Boolean get() = end < total
        val nextOffset: Int? get() = if (end < total) end else null
    }

    fun window(text: String, offset: Int, maxChars: Int): Window {
        val total = text.length
        var start = offset.coerceIn(0, total)
        // Never start on the low half of a pair.
        if (start in 1 until total && Character.isLowSurrogate(text[start]) &&
            Character.isHighSurrogate(text[start - 1])
        ) {
            start -= 1
        }
        val size = maxChars.coerceAtLeast(1)
        var end = if (total - start <= size) total else start + size
        if (end < total) {
            val floor = start + size - size / 5
            val nl = text.lastIndexOf('\n', end - 1)
            if (nl >= floor && nl > start) end = nl + 1
            if (end < total && Character.isLowSurrogate(text[end]) &&
                Character.isHighSurrogate(text[end - 1])
            ) {
                // Step back off the pair — or, when the pair is all the window
                // holds, take it whole: a window must always advance.
                if (end > start + 1) end -= 1 else end += 1
            }
        }
        return Window(text.substring(start, end), start, end, total)
    }

    /**
     * What the model reads after a window. Empty when the whole text was
     * returned from the top — the common case stays noise-free.
     */
    fun trailer(w: Window): String {
        if (w.start == 0 && !w.hasMore) return ""
        val range = "characters ${w.start}–${w.end} of ${w.total}"
        return if (w.hasMore) {
            "\n\n[showing $range — more follows: call web_fetch again with the same url and offset=${w.end}]"
        } else {
            "\n\n[showing $range — end of content]"
        }
    }

    /** `offset` past the end is a usage error worth a clear sentence, not an empty answer. */
    fun offsetError(offset: Int, total: Int): String? =
        if (offset > 0 && offset >= total) {
            "offset $offset is past the end of the content ($total characters). Start again with offset=0."
        } else {
            null
        }

    /**
     * Split a total character budget over [count] excerpts: equal shares, never
     * below [floor] (a 200-character excerpt tells the model nothing), never
     * above [ceiling].
     */
    fun shareBudget(total: Int, count: Int, floor: Int = 800, ceiling: Int = 4000): Int {
        if (count <= 0) return 0
        return (total / count).coerceIn(floor, ceiling.coerceAtLeast(floor))
    }
}
