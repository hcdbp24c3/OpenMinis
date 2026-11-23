package com.openminis.app.agent

import com.openminis.app.ProductionSources
import com.openminis.app.agent.CommandRisk.Level
import com.openminis.app.config.ConfigRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m2-approval] When the shell asks, what it shows, and where it is wired. */
class ShellApprovalTest {

    @Test
    fun `read-only commands never ask`() {
        assertFalse(ShellApproval.needsApproval(Level.READ_ONLY, confirmRisky = true, confirmChanges = true))
    }

    @Test
    fun `by default only dangerous commands ask`() {
        assertTrue(ShellApproval.needsApproval(Level.DANGEROUS, confirmRisky = true, confirmChanges = false))
        assertFalse(ShellApproval.needsApproval(Level.NORMAL, confirmRisky = true, confirmChanges = false))
    }

    @Test
    fun `the careful switch asks for everything that is not read-only`() {
        assertTrue(ShellApproval.needsApproval(Level.NORMAL, confirmRisky = false, confirmChanges = true))
        assertTrue(ShellApproval.needsApproval(Level.DANGEROUS, confirmRisky = false, confirmChanges = true))
    }

    @Test
    fun `both switches off asks for nothing`() {
        for (level in Level.values()) {
            assertFalse(ShellApproval.needsApproval(level, confirmRisky = false, confirmChanges = false))
        }
    }

    @Test
    fun `the dialog shows the whole command, the directory and the reason`() {
        val verdict = CommandRisk.classify("git reset --hard")
        val change = ShellApproval.pendingChange("  git reset --hard\n", verdict, "/root/app")
        assertEquals("git reset --hard", change.caption)
        val item = change.items.single()
        assertEquals("in /root/app", item.path)
        assertEquals("git reset --hard", item.newDisplay)
        assertEquals(ConfigRisk.DESTRUCTIVE, item.risk)
        assertTrue(item.isApproved)
    }

    @Test
    fun `a very long command is cut with a note, not dropped`() {
        val long = "echo " + "x".repeat(5_000)
        val caption = ShellApproval.caption(long)
        assertTrue(caption.length < long.length)
        assertTrue(caption.contains("more characters"))
    }

    @Test
    fun `a sub agent is told to hand the command back`() {
        val text = ShellApproval.subAgentRefusal(CommandRisk.classify("rm -rf /"))
        assertTrue(text.contains("NOT run"))
        assertTrue(text.contains("parent agent"))
    }

    // ---------------------------------------------------------- source guards

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test
    fun `the gate sits before anything can run`() {
        val body = vm.substringAfter("private suspend fun executeShellCommand(")
        val gate = body.indexOf("ShellApproval.check(")
        val wrap = body.indexOf("SessionProject.wrapCommand(")
        val detect = body.indexOf("BashismDetector.detect(command)")
        val firstRun = body.indexOf("ExecutionCoordinator.execute(")
        assertTrue("executeShellCommand must consult the approval gate", gate > 0)
        assertTrue("judge the command the model wrote, before the cd line is added", gate < wrap)
        assertTrue(gate < detect)
        assertTrue("nothing may execute before the gate", gate < firstRun)
        assertTrue(body.substring(gate, wrap).contains("isSubAgent = helperConfig != null"))
    }
}
