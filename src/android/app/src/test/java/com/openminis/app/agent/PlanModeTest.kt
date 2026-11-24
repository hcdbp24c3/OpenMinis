package com.openminis.app.agent

import com.openminis.app.ProductionSources
import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m3-plan] What plan mode hides, what it refuses, and where it is enforced. */
class PlanModeTest {

    private fun shell(command: String): String =
        org.json.JSONObject().put("tool_title", "t").put("command", command).toString()

    @Test
    fun `the file-writing tools leave the schema only while planning`() {
        val tools = AgentTools.makeAgentTools()
        val names = tools.map { it.name }
        assertTrue(names.contains("file_write"))
        assertEquals(tools, PlanMode.filter(tools, false))
        val planning = PlanMode.filter(tools, true).map { it.name }
        for (name in PlanMode.WRITE_TOOLS) assertFalse(name, planning.contains(name))
        assertTrue(planning.contains("shell_execute"))
        assertTrue(planning.contains("file_read"))
    }

    @Test
    fun `write tools are refused at dispatch too`() {
        for (name in PlanMode.WRITE_TOOLS) assertNotNull(name, PlanMode.refusalFor(name, "{}"))
        assertNull(PlanMode.refusalFor("file_read", "{}"))
        assertNull(PlanMode.refusalFor("turn_changes", "{}"))
        // [T-ask-merge] user_ask is the one ask tool; planning must not block it.
        assertNull(PlanMode.refusalFor("user_ask", "{}"))
    }

    @Test
    fun `only read-only shell commands run`() {
        assertNull(PlanMode.refusalFor("shell_execute", shell("ls -la && git status")))
        assertNull(PlanMode.refusalFor("shell_execute", shell("rg -n TODO src | head -20")))
        assertNotNull(PlanMode.refusalFor("shell_execute", shell("npm install")))
        assertNotNull(PlanMode.refusalFor("shell_execute", shell("echo hi > out.txt")))
        assertNotNull(PlanMode.refusalFor("shell_execute", shell("git commit -m x")))
        assertNotNull(PlanMode.refusalFor("shell_execute", shell("ls; rm -rf build")))
    }

    @Test
    fun `plan mode is enforced in the schema, the dispatcher and the prompt`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("PlanMode.filterTools(context, fsSessionId, AgentTools.makeAgentTools("))
        val refusal = vm.indexOf("PlanMode.refusal(context, fsSessionId, name, argsJson)")
        val dispatch = vm.indexOf("FileReadTool.NAME -> {")
        assertTrue("the refusal must come before any tool is dispatched", refusal in 1 until dispatch)
        val project = vm.indexOf("SessionProject.promptFragmentFor(context, fsSessionId)")
        val plan = vm.indexOf("PlanMode.promptFragmentFor(context, fsSessionId)")
        assertTrue("the plan note belongs in the runtime suffix, after the project block", project in 1 until plan)
        assertTrue(vm.contains("\"plan\" -> com.openminis.app.agent.PlanMode.toggle(context, activeSessionId)"))
    }
}
