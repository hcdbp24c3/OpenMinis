package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openminis.app.ProductionSources

/**
 * [T-android-scheduled-resume-nudge] Port of iOS c9131e862's resume nudge: after a
 * scheduled fire inserted between tool calls is answered, one hidden
 * <system-reminder> continues the interrupted task.
 *
 * [T-android-nudge-parity-stale] The nudge used to be asserted byte-for-byte against
 * the iOS source:
 *
 *     ios.substringAfter("static let scheduledResumeNudgeText =")
 *        .substringAfter("\"").substringBefore("\"\n")
 *
 * iOS 1.14 no longer carries that symbol — a grep of the whole `src/ios` tree for
 * `scheduledResumeNudgeText` AND for the text itself finds nothing — so the marker was
 * always absent, `substringAfter` on a missing marker hands back the ENTIRE file, and
 * the assertion compared the Android constant against a chunk of Swift starting at the
 * first `"` in the file (`ChatStore") /// [T-memory-enabled-new-session-bug DIAG] …`).
 * It could only ever fail, and it was failing for a reason that said nothing about
 * Android.
 *
 * What remains here is what Android itself must guarantee: the reminder is hidden, it
 * is carried verbatim by the constant, and it is gated — sent once, on a clean stop with
 * content, and not while a user prompt is waiting for the model.
 */
class ScheduledResumeNudgeTest {

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test fun `nudge is a hidden system reminder`() {
        val nudge = ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT
        assertTrue(nudge, nudge.startsWith("<system-reminder>"))
        assertTrue(nudge, nudge.endsWith("</system-reminder>"))
        // It is appended as a "user" message; the reminder tags are what keep it out of
        // the transcript, so a stray tag in the middle would show a bubble.
        assertFalse(nudge, nudge.drop(1).contains("<system-reminder>"))
    }

    @Test fun `owed only after a scheduled insert, cleared by a user batch`() {
        assertTrue(vm.contains("var scheduledResumeNudgeOwed = false"))
        assertTrue(vm.contains("scheduledResumeNudgeOwed = scheduledFire != null"))
    }

    @Test fun `sent once, on a clean stop with content, not when a user prompt waits`() {
        val gate = vm.substringAfter("val cleanStop = turnFinishReason == \"stop\" || turnFinishReason == \"end_turn\"")
            .substringBefore("// Auto-title after first exchange")
        assertTrue(gate.contains("if (scheduledResumeNudgeOwed && !isEmptyTurn && cleanStop && !userPromptWaiting) {"))
        assertTrue(gate.contains("scheduledResumeNudgeOwed = false"))
        assertTrue(gate.contains("chatRepository.appendMessage(nudgeSid, \"user\""))
        assertTrue(gate.trimEnd().endsWith("continue\n                }") || gate.contains("continue"))
    }
}
