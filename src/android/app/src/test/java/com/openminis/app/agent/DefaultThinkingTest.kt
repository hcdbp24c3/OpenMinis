package com.openminis.app.agent

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-default-thinking] The app-wide default thinking level only fills a gap. */
class DefaultThinkingTest {

    @Test
    fun parseReadsNamesInEitherCaseAndFallsBackToOff() {
        assertEquals(ThinkingLevel.HIGH, DefaultThinking.parse("HIGH"))
        assertEquals(ThinkingLevel.HIGH, DefaultThinking.parse("high"))
        assertEquals(ThinkingLevel.AUTO, DefaultThinking.parse(" AUTO "))
        assertEquals(ThinkingLevel.OFF, DefaultThinking.parse(null))
        assertEquals(ThinkingLevel.OFF, DefaultThinking.parse(""))
        assertEquals(ThinkingLevel.OFF, DefaultThinking.parse("nonsense"))
    }

    @Test
    fun choicesCoverEveryLevelOnceStartingWithOff() {
        assertEquals(ThinkingLevel.OFF, DefaultThinking.CHOICES[0])
        assertEquals(ThinkingLevel.entries.size, DefaultThinking.CHOICES.size)
        assertEquals(ThinkingLevel.entries.toSet(), DefaultThinking.CHOICES.toSet())
    }

    @Test
    fun aSessionChoiceAlwaysWins() {
        assertNull(DefaultThinking.fallbackFor(ThinkingLevel.OFF, null, ThinkingLevel.HIGH))
        assertNull(DefaultThinking.fallbackFor(ThinkingLevel.LOW, ThinkingLevel.MAX, ThinkingLevel.HIGH))
    }

    @Test
    fun aGroupDefaultWinsOverTheAppDefault() {
        assertNull(DefaultThinking.fallbackFor(null, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH))
    }

    @Test
    fun theAppDefaultFillsTheGap() {
        assertEquals(ThinkingLevel.HIGH, DefaultThinking.fallbackFor(null, null, ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.OFF, DefaultThinking.fallbackFor(null, null, ThinkingLevel.OFF))
    }

    @Test
    fun clampTurnsThinkingOffForAModelThatCannotReason() {
        assertEquals(
            ThinkingLevel.OFF,
            DefaultThinking.clampToModel(ThinkingLevel.HIGH, false, ThinkingLevel.XHIGH),
        )
        assertEquals(
            ThinkingLevel.OFF,
            DefaultThinking.clampToModel(ThinkingLevel.AUTO, false, ThinkingLevel.XHIGH),
        )
        assertEquals(
            ThinkingLevel.OFF,
            DefaultThinking.clampToModel(ThinkingLevel.AUTO, true, ThinkingLevel.OFF),
        )
    }

    @Test
    fun clampLowersATierAboveTheCeilingAndKeepsTheRest() {
        assertEquals(
            ThinkingLevel.HIGH,
            DefaultThinking.clampToModel(ThinkingLevel.ULTRA, true, ThinkingLevel.HIGH),
        )
        assertEquals(
            ThinkingLevel.MEDIUM,
            DefaultThinking.clampToModel(ThinkingLevel.MEDIUM, true, ThinkingLevel.HIGH),
        )
        assertEquals(
            ThinkingLevel.OFF,
            DefaultThinking.clampToModel(ThinkingLevel.OFF, true, ThinkingLevel.HIGH),
        )
    }

    @Test
    fun clampLeavesAutoAloneBecauseItSendsNoTier() {
        assertEquals(
            ThinkingLevel.AUTO,
            DefaultThinking.clampToModel(ThinkingLevel.AUTO, true, ThinkingLevel.LOW),
        )
    }

    @Test
    fun theViewModelAppliesTheDefaultOnlyAsAFallback() {
        val src: String = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val helper: Int = src.indexOf("private fun applyUserDefaultThinkingLevel(")
        assertTrue("helper missing", helper >= 0)
        val close: Int = src.indexOf("\n    }\n", helper)
        assertTrue("helper end missing", close > helper)
        val body: String = src.substring(helper, close)
        assertTrue(body.contains("DefaultThinking.fallbackFor("))
        assertTrue(body.contains("DefaultThinking.clampToModel("))
        // It must never create a session row or write the DB: a draft holds the
        // level in memory until the first send flushes it.
        assertTrue(!body.contains("ensureSession()"))
        assertTrue(!body.contains("updateThinkingOverride"))
    }
}
