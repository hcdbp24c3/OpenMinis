package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [T-coding-m2-undo] The journal behind file_undo, against a real temp dir. */
class FileJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun journal(): File = File(tmp.root, "journal")

    private fun write(file: File, linuxPath: String, text: String) {
        FileJournal.record(journal(), linuxPath, file)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `undo puts back what the file held before the change`() {
        val f = tmp.newFile("a.txt").also { it.writeText("one") }
        write(f, "/root/a.txt", "two")
        val result = FileJournal.undo(journal(), null)
        assertTrue(result.message, result.success)
        assertEquals("one", f.readText())
        assertTrue(FileJournal.entries(journal()).isEmpty())
    }

    @Test
    fun `undo removes a file the change created`() {
        val f = File(tmp.root, "new.txt")
        write(f, "/root/new.txt", "hello")
        assertTrue(FileJournal.undo(journal(), "/root/new.txt").success)
        assertFalse(f.exists())
    }

    @Test
    fun `undo steps back one change at a time, newest first`() {
        val f = tmp.newFile("a.txt").also { it.writeText("v1") }
        write(f, "/root/a.txt", "v2")
        write(f, "/root/a.txt", "v3")
        val first = FileJournal.undo(journal(), null)
        assertEquals("v2", f.readText())
        assertTrue(first.message.contains("1 earlier change"))
        FileJournal.undo(journal(), null)
        assertEquals("v1", f.readText())
        assertFalse(FileJournal.undo(journal(), null).success)
    }

    @Test
    fun `a path picks that file's last change and leaves the others`() {
        val a = tmp.newFile("a.txt").also { it.writeText("a1") }
        val b = tmp.newFile("b.txt").also { it.writeText("b1") }
        write(a, "/root/a.txt", "a2")
        write(b, "/root/b.txt", "b2")
        assertTrue(FileJournal.undo(journal(), "/root/a.txt").success)
        assertEquals("a1", a.readText())
        assertEquals("b2", b.readText())
        assertEquals(listOf("/root/b.txt"), FileJournal.entries(journal()).map { it.linuxPath })
    }

    @Test
    fun `an unknown path and an empty journal both say there is nothing to undo`() {
        assertFalse(FileJournal.undo(journal(), null).success)
        val a = tmp.newFile("a.txt")
        write(a, "/root/a.txt", "x")
        val result = FileJournal.undo(journal(), "/root/other.txt")
        assertFalse(result.success)
        assertTrue(result.message.contains("/root/other.txt"))
    }

    @Test
    fun `only the newest changes are kept`() {
        val f = tmp.newFile("a.txt").also { it.writeText("0") }
        for (i in 1..(FileJournal.MAX_ENTRIES + 5)) write(f, "/root/a.txt", "$i")
        assertEquals(FileJournal.MAX_ENTRIES, FileJournal.entries(journal()).size)
        val backups = journal().listFiles { file -> file.name.endsWith(".bak") }.orEmpty()
        assertEquals(FileJournal.MAX_ENTRIES, backups.size)
    }

    @Test
    fun `a path that would break the index is skipped, and the write still happens`() {
        val f = tmp.newFile("a.txt").also { it.writeText("one") }
        write(f, "/root/a\tb.txt", "two")
        assertEquals("two", f.readText())
        assertTrue(FileJournal.entries(journal()).isEmpty())
    }

    @Test
    fun `a missing saved copy is reported instead of writing an empty file`() {
        val f = tmp.newFile("a.txt").also { it.writeText("one") }
        write(f, "/root/a.txt", "two")
        journal().listFiles { file -> file.name.endsWith(".bak") }.orEmpty().forEach { it.delete() }
        val result = FileJournal.undo(journal(), null)
        assertFalse(result.success)
        assertEquals("two", f.readText())
    }

    // ---------------------------------------------------------- source guards

    @Test
    fun `both file tools record before they write`() {
        val write = ProductionSources.read("tools/FileWriteTool.kt")
        assertTrue(write.indexOf("FileJournal.record(") in 1 until write.indexOf("file.appendText(content)"))
        val edit = ProductionSources.read("tools/FileEditTool.kt")
        assertTrue(edit.indexOf("FileJournal.record(") in 1 until edit.indexOf("file.writeText(newContent)"))
    }

    @Test
    fun `file_undo is offered and dispatched`() {
        assertTrue(AgentTools.makeAgentTools().any { it.name == FileUndoTool.NAME })
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("FileUndoTool.execute(argsJson, fsSessionId, context)"))
    }
}
