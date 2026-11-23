package com.openminis.app.agent

import com.openminis.app.ProductionSources
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.TurnChangesTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m3-shadow] The shadow repository's scripts, parsing and wiring. */
class ShadowGitTest {

    private fun args(vararg pairs: Pair<String, String>): String {
        val obj = org.json.JSONObject()
        for ((k, v) in pairs) obj.put(k, v)
        return obj.toString()
    }

    @Test
    fun `the shadow repository is outside the project and never its own git dir`() {
        val dir = ShadowGit.gitDir("abc-123")
        assertEquals(ShadowGit.ROOT + "/abc-123", dir)
        assertEquals(ShadowGit.ROOT + "/a_b_c__", ShadowGit.gitDir("a/b c.."))
        val pre = ShadowGit.preamble("s1", "/root/my app")
        assertTrue(pre.contains("GIT_DIR='" + ShadowGit.ROOT + "/s1'"))
        assertTrue(pre.contains("GIT_WORK_TREE='/root/my app'"))
        assertTrue(pre.contains("cd '/root/my app' || exit 97"))
    }

    @Test
    fun `a quote in the project path cannot break out of the script`() {
        val pre = ShadowGit.preamble("s1", "/root/it's")
        assertTrue(pre.contains("GIT_WORK_TREE='/root/it'\\''s'"))
    }

    @Test
    fun `every script starts from the same environment`() {
        val pre = ShadowGit.preamble("s", "/root/p")
        for (script in listOf(
            ShadowGit.snapshotScript("s", "/root/p"),
            ShadowGit.diffScript("s", "/root/p"),
            ShadowGit.revertScript("s", "/root/p"),
        )) {
            assertTrue(script.startsWith(pre))
            assertFalse("no bash-only syntax", script.contains("[[") || script.contains("<<<"))
        }
        assertTrue(ShadowGit.snapshotScript("s", "/root/p").contains("git update-ref " + ShadowGit.REF))
        assertTrue(ShadowGit.revertScript("s", "/root/p").contains("--diff-filter=A"))
    }

    @Test
    fun `numstat lines are parsed, binary files included`() {
        val files = ShadowGit.parseNumstat("3\t1\tsrc/a.kt\n-\t-\timg/logo.png\n\n0\t7\tdir with space/b.txt\ngarbage\n")
        assertEquals(
            listOf(
                ShadowGit.FileChange("src/a.kt", 3, 1, false),
                ShadowGit.FileChange("img/logo.png", 0, 0, true),
                ShadowGit.FileChange("dir with space/b.txt", 0, 7, false),
            ),
            files,
        )
    }

    @Test
    fun `diff output is split into files and patch`() {
        val out = "noise\n" + ShadowGit.MARK_NUMSTAT + "\n2\t0\ta.txt\n" + ShadowGit.MARK_PATCH +
            "\ndiff --git a/a.txt b/a.txt\n+one\n+two\n"
        val changes = ShadowGit.parseDiff(out)
        assertNotNull(changes)
        assertEquals(1, changes!!.files.size)
        assertEquals(2, changes.added)
        assertTrue(changes.patch.startsWith("diff --git"))
        assertFalse(changes.truncated)
        assertEquals("1 file changed, +2 -0", ShadowGit.summary(changes))

        assertNull(ShadowGit.parseDiff(ShadowGit.MARK_NONE + "\n"))
        val long = ShadowGit.parseDiff(
            ShadowGit.MARK_NUMSTAT + "\n" + ShadowGit.MARK_PATCH + "\n" + "x".repeat(ShadowGit.PATCH_MAX_BYTES + 1),
        )
        assertTrue(long!!.truncated)
        assertEquals(ShadowGit.PATCH_MAX_BYTES, long.patch.length)
    }

    @Test
    fun `only calls that can change files trigger a snapshot`() {
        assertTrue(ShadowGit.isMutating("file_write", "{}"))
        assertTrue(ShadowGit.isMutating("file_edit", "{}"))
        assertTrue(ShadowGit.isMutating("shell_execute", args("command" to "npm install")))
        assertFalse(ShadowGit.isMutating("shell_execute", args("command" to "git status")))
        assertFalse(ShadowGit.isMutating("file_read", "{}"))
        assertFalse(ShadowGit.isMutating("turn_changes", "{}"))
    }

    @Test
    fun `the snapshot directory is the call's cwd, else the stored project`() {
        assertEquals("/root/app", ShadowGit.targetDir("file_edit", "{}", "/root/app"))
        assertNull(ShadowGit.targetDir("file_edit", "{}", null))
        assertEquals("/root/new", ShadowGit.targetDir("shell_execute", args("command" to "x", "cwd" to "/root/new"), "/root/app"))
        assertEquals("/root/app", ShadowGit.targetDir("shell_execute", args("command" to "x"), "/root/app"))
        assertNull(ShadowGit.targetDir("shell_execute", args("command" to "x", "cwd" to "/root"), "/root/app"))
        assertNull(ShadowGit.targetDir("shell_execute", args("command" to "x", "cwd" to "relative/dir"), "/root/app"))
    }

    @Test
    fun `a turn owes a snapshot until one is taken`() {
        val session = "shadow-test-" + System.nanoTime()
        assertNull(ShadowGit.snapshotDir(session))
        ShadowGit.onUserTurn(session)
        assertNull(ShadowGit.snapshotDir(session))
        assertEquals(ShadowGit.Turn(pending = true), ShadowGit.turns.value[session])
    }

    @Test
    fun `the tool result lists files and cuts a long diff`() {
        assertTrue(TurnChangesTool.render(null).startsWith("No snapshot"))
        val none = ShadowGit.Changes(emptyList(), "", false)
        assertEquals("No changes since the turn started.", TurnChangesTool.render(none))
        val some = ShadowGit.Changes(
            listOf(ShadowGit.FileChange("a.kt", 1, 2, false), ShadowGit.FileChange("b.png", 0, 0, true)),
            "y".repeat(TurnChangesTool.MAX_PATCH_CHARS + 10),
            false,
        )
        val text = TurnChangesTool.render(some)
        assertTrue(text.startsWith("2 files changed, +1 -2"))
        assertTrue(text.contains("  +1 -2 a.kt"))
        assertTrue(text.contains("  (binary) b.png"))
        assertTrue(text.endsWith("[diff truncated — read the remaining files directly]"))
    }

    // ---------------------------------------------------------- source guards

    @Test
    fun `the snapshot hook runs before dispatch and a user turn re-arms it`() {
        assertTrue(AgentTools.makeAgentTools().any { it.name == TurnChangesTool.NAME })
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val plan = vm.indexOf("PlanMode.refusal(context, fsSessionId, name, argsJson)")
        val hook = vm.indexOf("ShadowGit.beforeTool(context, activeSessionId, fsSessionId, name, argsJson)")
        val dispatch = vm.indexOf("FileReadTool.NAME -> {")
        assertTrue("a refused call must not cost a snapshot", plan in 1 until hook)
        assertTrue("the snapshot must exist before the tool runs", hook < dispatch)
        assertTrue(vm.contains("ShadowGit.onUserTurn(activeSessionId)"))
        assertTrue(vm.contains("TurnChangesTool.execute(argsJson, fsSessionId)"))
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("SessionCodingBar(sessionId = { viewModel.currentSessionId })"))
    }
}
