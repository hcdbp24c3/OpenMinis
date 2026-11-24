package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-ask-merge] ask_user_question folded into user_ask: one tool, one switch. */
class UserAskMergeTest {

    // ── Switch migration ──────────────────────────────────────────────────

    @Test fun `an untouched install writes nothing but the marker`() {
        val d = UserAskSwitchMigration.decide(alreadyMigrated = false, legacyAskEnabled = null, userAskEnabled = null)
        assertTrue(d.markMigrated)
        assertNull(d.writeUserAskEnabled)
        assertTrue(UserAskSwitchMigration.effectiveEnabled(null, null))
    }

    @Test fun `the old switch off turns the merged tool off`() {
        for (userAsk in listOf<Boolean?>(null, true)) {
            val d = UserAskSwitchMigration.decide(false, legacyAskEnabled = false, userAskEnabled = userAsk)
            assertTrue(d.markMigrated)
            assertEquals(false, d.writeUserAskEnabled)
            assertFalse(UserAskSwitchMigration.effectiveEnabled(false, userAsk))
        }
    }

    @Test fun `user_ask already off stays off whatever the old switch said`() {
        for (legacy in listOf<Boolean?>(null, true, false)) {
            val d = UserAskSwitchMigration.decide(false, legacyAskEnabled = legacy, userAskEnabled = false)
            assertTrue(d.markMigrated)
            assertNull("nothing to write, it is already off", d.writeUserAskEnabled)
            assertFalse(UserAskSwitchMigration.effectiveEnabled(legacy, false))
        }
    }

    @Test fun `the old switch on never turns anything on or off`() {
        for (userAsk in listOf<Boolean?>(null, true, false)) {
            assertNull(UserAskSwitchMigration.decide(false, legacyAskEnabled = true, userAskEnabled = userAsk).writeUserAskEnabled)
        }
        assertTrue(UserAskSwitchMigration.effectiveEnabled(true, null))
        assertTrue(UserAskSwitchMigration.effectiveEnabled(true, true))
    }

    @Test fun `it runs once - a later re-enable is not undone by the stale old value`() {
        val d = UserAskSwitchMigration.decide(alreadyMigrated = true, legacyAskEnabled = false, userAskEnabled = true)
        assertFalse(d.markMigrated)
        assertNull(d.writeUserAskEnabled)
    }

    @Test fun `disabled either way ends disabled, for every stored combination`() {
        val values = listOf<Boolean?>(null, true, false)
        for (legacy in values) for (userAsk in values) {
            val expected = legacy != false && userAsk != false
            assertEquals("legacy=$legacy userAsk=$userAsk", expected, UserAskSwitchMigration.effectiveEnabled(legacy, userAsk))
        }
    }

    @Test fun `the keys are the removed switch's and a new marker`() {
        assertEquals("agent.tools.ask.enabled", UserAskSwitchMigration.LEGACY_ENABLED_KEY)
        assertFalse(UserAskSwitchMigration.MIGRATED_KEY == UserAskSwitchMigration.LEGACY_ENABLED_KEY)
        assertFalse(UserAskSwitchMigration.MIGRATED_KEY == UserAskSettingsSpec.ENABLED_KEY)
    }

    // ── The removed tool's names ──────────────────────────────────────────

    @Test fun `a call to the removed tool is redirected to user_ask`() {
        assertTrue(LegacyAskTool.isLegacy("ask_user_question"))
        assertTrue(LegacyAskTool.isLegacy("AskUserQuestion"))
        assertFalse(LegacyAskTool.isLegacy("user_ask"))
        assertFalse(LegacyAskTool.isLegacy(null))
        val exposed = LegacyAskTool.redirectMessage(userAskExposed = true)
        assertTrue(exposed.startsWith("Error: "))
        assertTrue(exposed.contains("Call user_ask"))
        assertTrue(exposed.contains("multi_select"))
        assertTrue(exposed.length < 600)
        val hidden = LegacyAskTool.redirectMessage(userAskExposed = false)
        assertTrue(hidden.startsWith("Error: "))
        assertTrue(hidden.contains("user_ask is not available in this run"))
        assertFalse("must not tell a sub agent to call a tool it does not have", hidden.contains("Call user_ask"))
    }

    @Test fun `the model is offered exactly one ask tool`() {
        for (exposed in listOf(true, false)) {
            val names = AgentTools.makeAgentTools(userAskExposed = exposed).map { it.name }
            assertFalse("ask_user_question" in names)
            assertFalse("AskUserQuestion" in names)
            assertEquals(if (exposed) 1 else 0, names.count { it == "user_ask" })
        }
        assertFalse(AgentTools.makeAgentTools(userAskExposed = true, isHelper = true).any { it.name == "user_ask" })
    }

    @Test fun `one switch governs the surviving and the removed names`() {
        assertEquals(AgentToolSwitch.USER_ASK, AgentToolSwitch.governing("user_ask"))
        assertEquals(AgentToolSwitch.USER_ASK, AgentToolSwitch.governing("ask_user_question"))
        assertEquals(AgentToolSwitch.USER_ASK, AgentToolSwitch.governing("AskUserQuestion"))
        assertFalse(AgentToolSwitch.entries.any { it.key == UserAskSwitchMigration.LEGACY_ENABLED_KEY })
    }

    // ── Source guards ─────────────────────────────────────────────────────

    @Test fun `the removed tool's code is gone and its call is refused at dispatch`() {
        val root = ProductionSources.mainRoot()!!
        assertFalse(root.resolve("tools/AskUserQuestion.kt").exists())
        assertFalse(root.resolve("ui/chat/AskUserQuestionsSheet.kt").exists())
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertFalse(vm.contains("executeAskUserQuestion"))
        assertFalse(vm.contains("pendingUserQuestions"))
        assertFalse(vm.contains("askEnabled"))
        assertTrue(vm.contains("LegacyAskTool.redirectMessage(userAskExposedThisRun)"))
        assertFalse(ProductionSources.read("ui/chat/ChatScreen.kt").contains("AskUserQuestionsSheet"))
        assertFalse(ProductionSources.read("tools/AgentTools.kt").contains("askEnabled"))
        assertFalse(ProductionSources.read("ui/settings/AgentToolsSettingsScreen.kt").contains("AgentToolSwitch.ASK"))
    }

    @Test fun `the switch migration runs at app start and before the switch is read`() {
        val sw = ProductionSources.read("tools/AgentToolSwitch.kt")
        val legacy = sw.substringAfter("fun migrateLegacyIfNeeded(context: Context) {").substringBefore("\n        }\n")
        assertTrue(
            "must run before the granular migration's early return",
            legacy.indexOf("migrateAskMergeIfNeeded(context)") in 0 until legacy.indexOf("if (p.getBoolean(MIGRATION_KEY, false)) return"),
        )
        val read = sw.substringAfter("fun isEnabled(context: Context): Boolean").substringBefore("fun setEnabled(")
        assertTrue(read.contains("if (this == USER_ASK) migrateAskMergeIfNeeded(context)"))
        // The stored values go through the pure, unit-tested decision.
        assertTrue(sw.contains("val m = com.openminis.app.agent.userask.UserAskSwitchMigration\n"))
        assertTrue(sw.contains("val d = m.decide("))
    }

    @Test fun `old transcripts still get a title, icon and colour for the removed name`() {
        val fmt = ProductionSources.read("ui/chat/ChatToolFormatting.kt")
        assertEquals(7, Regex(""""ask_user_question", "AskUserQuestion" -> """).findAll(fmt).count())
        assertTrue(fmt.contains(""""ask_user_question", "AskUserQuestion" -> "Ask You""""))
    }

    @Test fun `plan mode points at the surviving tool`() {
        assertTrue(com.openminis.app.agent.PlanMode.PROMPT_FRAGMENT.contains("If user_ask is available"))
        assertFalse(com.openminis.app.agent.PlanMode.PROMPT_FRAGMENT.contains("ask_user_question"))
    }
}
