package com.openminis.app.agent

import com.openminis.app.ProductionSources
import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-coding-m1-project] The pure half of [SessionProject] — what counts as a
 * working directory, how a command is wrapped, how the prompt block is built —
 * plus source guards for the two places ChatViewModel wires it in.
 */
class SessionProjectTest {

    // ------------------------------------------------------------- normalize

    @Test
    fun `normalize trims and drops trailing slashes`() {
        assertEquals("/var/minis/workspace/repo", SessionProject.normalize("  /var/minis/workspace/repo/  "))
        assertEquals("/a/b", SessionProject.normalize("/a/b///"))
        assertEquals("/", SessionProject.normalize("/"))
        assertEquals("/", SessionProject.normalize("//"))
    }

    @Test
    fun `normalize treats blank and JSON null as absent`() {
        assertNull(SessionProject.normalize(null))
        assertNull(SessionProject.normalize(""))
        assertNull(SessionProject.normalize("   "))
        // JSONObject.optString returns the string "null" for an explicit null.
        assertNull(SessionProject.normalize("null"))
    }

    // ------------------------------------------------------------ validation

    @Test
    fun `an absolute single-line path is accepted`() {
        assertNull(SessionProject.validationError("/var/minis/mounts/my repo"))
        assertNull(SessionProject.validationError("/root/it's here"))
        assertNull(SessionProject.validationError("/"))
    }

    @Test
    fun `relative, multi-line, dot-dot and oversized paths are refused`() {
        assertNotNull(SessionProject.validationError("repo"))
        assertNotNull(SessionProject.validationError("~/repo"))
        assertNotNull(SessionProject.validationError("/a\nrm -rf /"))
        assertNotNull(SessionProject.validationError("/a\rb"))
        assertNotNull(SessionProject.validationError("/a/../b"))
        assertNotNull(SessionProject.validationError("/a/.."))
        assertNotNull(SessionProject.validationError("/" + "x".repeat(SessionProject.DIR_MAX_LENGTH)))
    }

    @Test
    fun `a name that merely contains dots is not a dot-dot segment`() {
        assertNull(SessionProject.validationError("/a/..hidden/b"))
        assertNull(SessionProject.validationError("/a/v1..2"))
    }

    // --------------------------------------------------------------- quoting

    @Test
    fun `shellQuote survives spaces, quotes and expansion characters`() {
        assertEquals("'/a b'", SessionProject.shellQuote("/a b"))
        assertEquals("'/it'\\''s'", SessionProject.shellQuote("/it's"))
        assertEquals("'/\$HOME/`id`'", SessionProject.shellQuote("/\$HOME/`id`"))
    }

    @Test
    fun `wrapCommand puts the cd on its own first line and keeps the command verbatim`() {
        val command = "ls -la\necho done"
        val wrapped = SessionProject.wrapCommand("/var/minis/workspace/repo", command)
        val lines = wrapped.lines()
        assertEquals("cd '/var/minis/workspace/repo' || exit ${SessionProject.CD_FAILED_EXIT}", lines.first())
        assertEquals(command, lines.drop(1).joinToString("\n"))
    }

    @Test
    fun `the cd line has no brace group or bash-only syntax`() {
        // The agent shell is BusyBox ash and BashismDetector inspects the whole
        // command; the injected line must never be what trips it.
        val first = SessionProject.wrapCommand("/p", "true").lines().first()
        assertFalse(first.contains("{"))
        assertFalse(first.contains("[["))
    }

    // ---------------------------------------------------------------- prompt

    @Test
    fun `instructions that fit are returned trimmed and untouched`() {
        assertEquals("be kind", SessionProject.truncateInstructions("\n be kind \n", "/p/AGENTS.md"))
    }

    @Test
    fun `long instructions are cut and say where the rest is`() {
        val text = "x".repeat(SessionProject.INSTRUCTIONS_MAX_CHARS + 500)
        val out = SessionProject.truncateInstructions(text, "/p/AGENTS.md")
        assertTrue(out.startsWith("x".repeat(SessionProject.INSTRUCTIONS_MAX_CHARS)))
        assertTrue(out.contains("truncated"))
        assertTrue(out.contains("/p/AGENTS.md"))
        assertTrue(out.length < text.length)
    }

    @Test
    fun `the fragment without an instructions file names the directory and the guidance only`() {
        val f = SessionProject.promptFragment("/var/minis/workspace/repo", null, null)
        assertTrue(f.startsWith("- Project directory: /var/minis/workspace/repo"))
        assertTrue(f.contains(SessionProject.CODING_GUIDANCE))
        assertFalse(f.contains("<project_instructions>"))
    }

    @Test
    fun `the fragment with an instructions file ends with its body`() {
        val f = SessionProject.promptFragment("/p/", "AGENTS.md", "Run make test.")
        assertTrue(f.contains("/p/AGENTS.md"))
        assertTrue(f.trimEnd().endsWith("<project_instructions>\nRun make test.\n</project_instructions>"))
    }

    @Test
    fun `a blank instructions body is treated as no file`() {
        val f = SessionProject.promptFragment("/p", "AGENTS.md", "   ")
        assertFalse(f.contains("<project_instructions>"))
    }

    // ------------------------------------------------------------ tool schema

    @Test
    fun `shell_execute advertises an optional cwd`() {
        val shell = AgentTools.makeAgentTools().first { it.name == "shell_execute" }
        assertTrue(shell.parameters.containsKey("cwd"))
        assertFalse(shell.required.contains("cwd"))
        assertTrue(shell.propertyOrdering.contains("cwd"))
    }

    // ---------------------------------------------------------- source guards

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test
    fun `the shell tool resolves cwd before bashism detection and a sub agent never persists it`() {
        val body = vm.substringAfter("private suspend fun executeShellCommand(")
        val resolve = body.indexOf("SessionProject.resolveForShell(")
        val detect = body.indexOf("BashismDetector.detect(command)")
        assertTrue("cwd must be resolved in executeShellCommand", resolve > 0)
        assertTrue("the cd must be in place before the command is inspected", resolve < detect)
        assertTrue(body.substring(resolve, detect).contains("persist = helperConfig == null"))
        assertTrue(body.substring(resolve, detect).contains("SessionProject.wrapCommand("))
    }

    @Test
    fun `the project block is appended after the runtime context, not into the cached base`() {
        val runtime = vm.indexOf("append(\"\\n\\nRuntime context:\\n\")")
        val project = vm.indexOf("SessionProject.promptFragmentFor(context, fsSessionId)")
        assertTrue(runtime > 0)
        assertTrue("project block must come after the Runtime context header", project > runtime)
    }

    // git and ripgrep ship in the rootfs (scripts/prepare_android_sandbox.sh), so
    // the project guidance must not spend tokens telling the model to install them.
    @Test
    fun `coding guidance does not tell the model to install tools`() {
        assertFalse(SessionProject.CODING_GUIDANCE.contains("apk add"))
    }
}
