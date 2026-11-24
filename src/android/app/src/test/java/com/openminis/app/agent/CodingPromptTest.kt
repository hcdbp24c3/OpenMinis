package com.openminis.app.agent

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m5-prompt] [T-coding-m5-commands] */
class CodingPromptTest {
    private val vm: String = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test
    fun theCodingSectionIsPartOfTheStaticBasePrompt() {
        val section = vm.indexOf("\${com.openminis.app.agent.CodingPrompt.BASE_SECTION}")
        val runtime = vm.indexOf("append(\"\\n\\nRuntime context:\\n\")")
        assertTrue(section > 0)
        assertTrue("must sit in the cached head, before the per-session tail", section < runtime)
    }

    @Test
    fun theSectionNamesOnlyToolsThatExistAndStaysBounded() {
        val s = CodingPrompt.BASE_SECTION
        for (tool in listOf("file_read", "file_edit", "file_write", "file_undo", "todo_write", "turn_changes", "subagent_task", "repo_digest", "browser_use")) {
            assertTrue(tool, s.contains(tool))
        }
        assertFalse("the retired ask tool must not come back", s.contains("ask_user_question"))
        assertFalse("when to ask lives in the ask tool's own description", s.contains("user_ask"))
        assertFalse("the template must stay free of Kotlin interpolation", s.contains("$"))
        assertTrue(s.length < 6000)
        assertTrue(s.contains("ignore it for ordinary chat"))
    }

    @Test
    fun everyBuiltInBriefCarriesTheEnvironmentNotesAndFits() {
        for (spec in SubAgentDefinition.makeBuiltIns()) {
            assertTrue(spec.id, spec.instructions.endsWith(CodingPrompt.SUB_AGENT_ENVIRONMENT))
            assertTrue(spec.id, spec.instructions.length <= SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)
        }
    }

    @Test
    fun aStoredDefaultFollowsTheCurrentBriefButAnEditedOneIsKept() {
        val spec = SubAgentDefinition.makeBuiltIns().first { it.id == SubAgentDefinition.SCOUT_ID }
        val before = spec.instructions.removeSuffix(CodingPrompt.SUB_AGENT_ENVIRONMENT)

        val blank = SubAgentRoster.normalize(listOf(spec.copy(instructions = "")))
        assertEquals(spec.instructions, blank.first { it.id == SubAgentDefinition.SCOUT_ID }.instructions)

        val old = SubAgentRoster.normalize(listOf(spec.copy(instructions = before)))
        assertEquals(spec.instructions, old.first { it.id == SubAgentDefinition.SCOUT_ID }.instructions)

        val mine = SubAgentRoster.normalize(listOf(spec.copy(instructions = "only list files")))
        assertEquals("only list files", mine.first { it.id == SubAgentDefinition.SCOUT_ID }.instructions)
    }

    @Test
    fun promptCommandsFillTheMessageBox() {
        assertEquals(listOf("init", "review", "test", "commit"), CodingCommands.ALL.map { it.id })
        assertNull(CodingCommands.promptFor("plan"))
        assertNull(CodingCommands.composerText("clear", "x"))
        val review = CodingCommands.promptFor("review")
        assertNotNull(review)
        assertEquals(review, CodingCommands.composerText("review", null))
        assertEquals(review, CodingCommands.composerText("review", "   "))
        assertEquals(review + "\n\nonly src/", CodingCommands.composerText("review", " only src/ "))
        for (c in CodingCommands.ALL) {
            assertTrue(c.id, c.prompt.length in 100..900)
            assertTrue("/" + c.id + " must be explained to the model", CodingPrompt.BASE_SECTION.contains("/" + c.id))
        }
    }

    @Test
    fun aTypedPromptCommandIsSentAsAMessage() {
        val fn = vm.substringAfter("fun tryExecuteInputAsSlashCommand(text: String): Boolean {").substringBefore("\n    }\n")
        val guard = fn.indexOf("if (com.openminis.app.agent.CodingCommands.promptFor(cmd.id) != null) return false")
        val run = fn.indexOf("executeSlashCommand(cmd)")
        assertTrue(guard in 1 until run)
    }
}
