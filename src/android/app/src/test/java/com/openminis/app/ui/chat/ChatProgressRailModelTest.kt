package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-chat-progress-rail] Messages -> ticks, viewport -> highlighted tick. */
class ChatProgressRailModelTest {

    private val m = ChatProgressRailModel

    @Test
    fun previewCollapsesWhitespaceAndCaps() {
        assertEquals("hello world", m.preview("  hello \n\n  world  "))
        val long = "word ".repeat(100)
        val p: String = m.preview(long)
        assertTrue(p.endsWith("…"))
        assertTrue(p.length <= m.PREVIEW_MAX_CHARS + 1)
        assertEquals("", m.preview("   \n "))
    }

    @Test
    fun previewOfAHugeMessageStaysCheapAndShort() {
        val huge = "a".repeat(400_000)
        val p: String = m.preview(huge)
        assertEquals(m.PREVIEW_MAX_CHARS + 1, p.length)
    }

    @Test
    fun shortChatsShowNoRail() {
        assertEquals(0, m.slotCount(0, 20))
        assertEquals(0, m.slotCount(2, 20))
        assertEquals(3, m.slotCount(3, 20))
    }

    @Test
    fun aRailWithNoRoomIsHidden() {
        assertEquals(0, m.slotCount(10, 2))
        assertEquals(0, m.slotCount(10, 0))
    }

    @Test
    fun slotsAreCappedByRoomAndByTheHardMaximum() {
        assertEquals(12, m.slotCount(50, 12))
        assertEquals(m.MAX_SLOTS, m.slotCount(500, 1000))
        assertEquals(7, m.slotCount(7, 1000))
    }

    @Test
    fun oneMarkerPerSlotWhenTheyFit() {
        for (i in 0 until 9) {
            assertEquals(i, m.slotStart(i, 9, 9))
            assertEquals(i, m.slotOf(i, 9, 9))
            assertEquals(i, m.tapTarget(i, 9, 9, -1))
        }
    }

    @Test
    fun condensedSlotsPartitionEveryMarkerInOrder() {
        val count = 137
        val slots = 28
        var previous = 0
        for (i in 0 until count) {
            val s: Int = m.slotOf(i, count, slots)
            assertTrue(s >= previous)
            assertTrue(s in 0 until slots)
            assertTrue(m.slotStart(s, count, slots) <= i)
            assertTrue(i < m.slotStart(s + 1, count, slots))
            previous = s
        }
        assertEquals(0, m.slotStart(0, count, slots))
        assertEquals(count, m.slotStart(slots, count, slots))
    }

    @Test
    fun everyMarkerIsReachableByRepeatedTapsOnItsSlot() {
        val count = 61
        val slots = 10
        val reached = HashSet<Int>()
        for (s in 0 until slots) {
            var active = -1
            var guard = 0
            while (guard++ < 50) {
                val t: Int = m.tapTarget(s, count, slots, active)
                if (!reached.add(t) && t == m.slotStart(s, count, slots)) break
                active = t
            }
        }
        assertEquals(count, reached.size)
    }

    @Test
    fun activeMarkerIsTheNewestUserMessageAtOrAboveTheTopRow() {
        val pos = intArrayOf(0, 12, 40, 41, 90)
        assertEquals(0, m.activeMarker(pos, 0))
        assertEquals(0, m.activeMarker(pos, 11))
        assertEquals(1, m.activeMarker(pos, 12))
        assertEquals(3, m.activeMarker(pos, 89))
        assertEquals(4, m.activeMarker(pos, 500))
    }

    @Test
    fun activeMarkerHandlesEdgesAndUnknownRows() {
        assertEquals(-1, m.activeMarker(IntArray(0), 5))
        // Viewport above the first user message: first marker.
        assertEquals(0, m.activeMarker(intArrayOf(4, 9), 1))
        // A marker whose row has not been published yet is never active.
        assertEquals(1, m.activeMarker(intArrayOf(0, 6, m.UNKNOWN_POS), 1000))
    }

    @Test
    fun touchMapsToASlotAndClampsAtTheEnds() {
        assertEquals(0, m.slotAt(-5f, 100f, 10))
        assertEquals(0, m.slotAt(9.9f, 100f, 10))
        assertEquals(5, m.slotAt(50f, 100f, 10))
        assertEquals(9, m.slotAt(100f, 100f, 10))
        assertEquals(9, m.slotAt(900f, 100f, 10))
    }

    @Test
    fun theRailNeverObservesScrollFromChatScreenItself() {
        // The highlighted tick must be derived inside the rail, so scrolling
        // redraws one small canvas instead of recomposing the chat screen.
        val rail: String = ProductionSources.read("ui/chat/ChatProgressRailUi.kt")
        assertTrue(rail.contains("derivedStateOf"))
        val screen: String = ProductionSources.read("ui/chat/ChatScreen.kt")
        val call: Int = screen.indexOf("ChatProgressRail(")
        assertTrue("rail is not mounted", call >= 0)
        val args: String = screen.substring(call, minOf(screen.length, call + 900))
        assertTrue(args.contains("markers = { railMarkers.value }"))
        assertTrue(args.contains("rowIndexByKey = { flatRowIndexByKey.value }"))
    }
}
