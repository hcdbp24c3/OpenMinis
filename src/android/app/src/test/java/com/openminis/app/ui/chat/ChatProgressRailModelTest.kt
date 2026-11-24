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

    // ── [T-chat-rail-scrub] ─────────────────────────────────────────────────

    @Test
    fun fingerMapsToOneMarkerPerSlotWhenTheyFit() {
        // 10 markers on 10 slots, rail 100px tall: 10px per marker.
        assertEquals(0, m.markerAt(0f, 100f, 10, 10))
        assertEquals(0, m.markerAt(9.9f, 100f, 10, 10))
        assertEquals(1, m.markerAt(10f, 100f, 10, 10))
        assertEquals(5, m.markerAt(55f, 100f, 10, 10))
        assertEquals(9, m.markerAt(99f, 100f, 10, 10))
    }

    @Test
    fun fingerClampsAtBothEndsOfTheRail() {
        assertEquals(0, m.markerAt(-40f, 100f, 10, 10))
        assertEquals(9, m.markerAt(100f, 100f, 10, 10))
        assertEquals(9, m.markerAt(5000f, 100f, 10, 10))
        assertEquals(0, m.markerAt(-40f, 280f, 137, 28))
        assertEquals(136, m.markerAt(5000f, 280f, 137, 28))
    }

    @Test
    fun fingerOnEmptyOrDegenerateRails() {
        assertEquals(-1, m.markerAt(10f, 100f, 0, 0))
        assertEquals(-1, m.markerAt(10f, 100f, 0, 5))
        assertEquals(0, m.markerAt(10f, 0f, 4, 4))
        assertEquals(0, m.markerAt(10f, 100f, 4, 0))
        assertEquals(0, m.markerAt(50f, 100f, 1, 1))
    }

    @Test
    fun onACondensedRailTheFingerResolvesIndividualMessages() {
        // 40 markers on 10 slots: 4 per slot, slot = 10px, 2.5px per marker.
        assertEquals(0, m.markerAt(0f, 100f, 40, 10))
        assertEquals(1, m.markerAt(3f, 100f, 40, 10))
        assertEquals(2, m.markerAt(6f, 100f, 40, 10))
        assertEquals(3, m.markerAt(9f, 100f, 40, 10))
        assertEquals(4, m.markerAt(10f, 100f, 40, 10))
        assertEquals(39, m.markerAt(99.5f, 100f, 40, 10))
    }

    @Test
    fun aSlowDragDownACondensedRailVisitsEveryMarkerInOrder() {
        val count = 137
        val slots = 28
        val height = 2800f
        val seen = HashSet<Int>()
        var previous = 0
        var y = 0f
        while (y < height) {
            val i: Int = m.markerAt(y, height, count, slots)
            assertTrue(i >= previous)
            assertTrue(i in 0 until count)
            // The pointed message always belongs to the tick under the finger.
            assertEquals(m.slotAt(y, height, slots), m.slotOf(i, count, slots))
            seen.add(i)
            previous = i
            y += 1f
        }
        assertEquals(count, seen.size)
    }

    @Test
    fun aTapJumpsToThePressedMarkerAndStepsWhenAlreadyThere() {
        // Not the active one: go there.
        assertEquals(7, m.tapTargetAt(7, 40, 10, 2))
        assertEquals(7, m.tapTargetAt(7, 40, 10, -1))
        // Already there, slot 1 covers 4..7: step forward, then wrap to 4.
        assertEquals(5, m.tapTargetAt(4, 40, 10, 4))
        assertEquals(7, m.tapTargetAt(6, 40, 10, 6))
        assertEquals(4, m.tapTargetAt(7, 40, 10, 7))
        // One marker per slot: tapping the active one stays on it.
        assertEquals(3, m.tapTargetAt(3, 9, 9, 3))
        // Out of range input is clamped; nothing to tap on an empty rail.
        assertEquals(8, m.tapTargetAt(99, 9, 9, -1))
        assertEquals(-1, m.tapTargetAt(0, 0, 0, -1))
    }

    @Test
    fun dragIsUndecidedInsideTheSlopThenVerticalScrubsAndSidewaysIsLeftAlone() {
        assertEquals(m.DRAG_UNDECIDED, m.dragDecision(0f, 0f, 8f))
        assertEquals(m.DRAG_UNDECIDED, m.dragDecision(7f, -7f, 8f))
        assertEquals(m.DRAG_SCRUB, m.dragDecision(2f, 9f, 8f))
        assertEquals(m.DRAG_SCRUB, m.dragDecision(-3f, -20f, 8f))
        assertEquals(m.DRAG_NOT_OURS, m.dragDecision(-9f, 2f, 8f))
        assertEquals(m.DRAG_NOT_OURS, m.dragDecision(30f, 12f, 8f))
        // A diagonal tie is never claimed.
        assertEquals(m.DRAG_NOT_OURS, m.dragDecision(10f, 10f, 8f))
    }

    @Test
    fun positionLabelIsOneBased() {
        assertEquals("1/40", m.positionLabel(0, 40))
        assertEquals("12/40", m.positionLabel(11, 40))
        assertEquals("40/40", m.positionLabel(39, 40))
        assertEquals("40/40", m.positionLabel(400, 40))
        assertEquals("", m.positionLabel(0, 0))
    }

    @Test
    fun bubbleSitsAboveTheFingerAndStaysInsideTheBounds() {
        // Bottom edge 10px under the finger.
        assertEquals(210, m.bubbleTop(300f, 100, 0, 1000, 10))
        // Near the top: pinned to the top bound.
        assertEquals(0, m.bubbleTop(20f, 100, 0, 1000, 10))
        // Near the bottom: pinned to the bottom bound.
        assertEquals(900, m.bubbleTop(995f, 100, 0, 1000, 10))
        // A bubble taller than the bounds, or not measured yet.
        assertEquals(0, m.bubbleTop(300f, 2000, 0, 1000, 10))
        assertEquals(310, m.bubbleTop(300f, 0, 0, 1000, 10))
    }

    @Test
    fun bubbleFollowsTheFingerPastTheRailUpToTheChatArea() {
        // Chat area from 250px above the rail's box to 1500px below its top.
        // Finger above the box: the bubble goes up with it.
        assertEquals(-190, m.bubbleTop(-100f, 100, -250, 1500, 10))
        // ...until the top of the chat area.
        assertEquals(-250, m.bubbleTop(-400f, 100, -250, 1500, 10))
        // Finger below the box (1000px tall): the bubble goes down with it.
        assertEquals(1110, m.bubbleTop(1200f, 100, -250, 1500, 10))
        // ...until the bottom of the chat area.
        assertEquals(1400, m.bubbleTop(1495f, 100, -250, 1500, 10))
        assertEquals(1400, m.bubbleTop(5000f, 100, -250, 1500, 10))
        // Inside the box nothing changes.
        assertEquals(210, m.bubbleTop(300f, 100, -250, 1500, 10))
    }

    @Test
    fun bubbleTallerThanTheChatAreaIsPinnedToItsTop() {
        assertEquals(-250, m.bubbleTop(300f, 2000, -250, 1500, 10))
        assertEquals(-250, m.bubbleTop(300f, 1750, -250, 1500, 10))
        // Empty or inverted bounds never throw.
        assertEquals(40, m.bubbleTop(300f, 100, 40, 40, 10))
        assertEquals(40, m.bubbleTop(300f, 100, 40, 0, 10))
    }

    @Test
    fun replyIsTheFirstAssistantTextBeforeTheNextUserMessage() {
        // 0 user, 1 tool-ish, 2 assistant(empty), 3 assistant, 4 user, 5 assistant
        val isReply = booleanArrayOf(false, false, false, true, false, true)
        assertEquals(3, m.replyIndex(0, 4, 6) { i -> isReply[i] })
        assertEquals(5, m.replyIndex(4, -1, 6) { i -> isReply[i] })
        // The reply of a LATER turn is never borrowed.
        assertEquals(-1, m.replyIndex(0, 3, 6) { i -> isReply[i] })
        // Last message, unknown message, stop past the end.
        assertEquals(-1, m.replyIndex(5, -1, 6) { i -> isReply[i] })
        assertEquals(-1, m.replyIndex(-1, -1, 6) { i -> isReply[i] })
        assertEquals(3, m.replyIndex(0, 99, 6) { i -> isReply[i] })
    }

    @Test
    fun firstLineSkipsLeadingBlankLinesAndStopsAtTheLineBreak() {
        assertEquals("Sure, here it is.", m.firstLine("\n\n  Sure,  here it is.\nSecond line", 60))
        assertEquals("", m.firstLine("  \n ", 60))
        assertEquals("", m.firstLine("", 60))
        val long: String = m.firstLine("x".repeat(500), 60)
        assertEquals(61, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun theRailScrubsWithoutRecomposingAndNeverClaimsSidewaysSwipes() {
        val rail: String = ProductionSources.read("ui/chat/ChatProgressRailUi.kt")
        // The finger position moves the bubble in the layout phase.
        assertTrue(rail.contains(".offset {"))
        // Jumps are conflated, so a fast drag cannot queue up scrolls.
        assertTrue(rail.contains("Channel.CONFLATED"))
        // A sideways drag leaves the gesture before anything is consumed.
        val notOurs: Int = rail.indexOf("DRAG_NOT_OURS) break")
        val consume: Int = rail.indexOf("current.consume()", notOurs)
        assertTrue(notOurs >= 0)
        assertTrue(consume > notOurs)
        // The bubble is not a Surface (a Surface swallows touches under it).
        assertTrue(!rail.contains("Surface("))
    }

    @Test
    fun theBubbleGoesTheMomentTheFingerLifts() {
        val rail: String = ProductionSources.read("ui/chat/ChatProgressRailUi.kt")
        // No linger timer after release.
        assertTrue(!rail.contains("PREVIEW_MS"))
        assertTrue(!rail.contains("delay("))
        // Every way out of the gesture clears the bubble.
        val cleanup: Int = rail.indexOf("} finally {")
        assertTrue(cleanup >= 0)
        val cleared: Int = rail.indexOf("shown.intValue = -1", cleanup)
        assertTrue(cleared > cleanup)
        assertEquals(cleared, rail.lastIndexOf("shown.intValue = -1"))
    }
}
