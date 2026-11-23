package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-coding-m2-todo] The task list's rules, the tool around them, and the wiring. */
class TodoListTest {

    private fun args(todos: Any?): JSONObject {
        val obj = JSONObject()
        obj.put("tool_title", "t")
        if (todos != null) obj.put("todos", todos)
        return obj
    }

    private fun item(content: String, status: String): JSONObject =
        JSONObject().put("content", content).put("status", status)

    @Test
    fun `a list arrives as an array or as a string holding one`() {
        val array = JSONArray().put(item("write tests", "in_progress")).put(item("ship", "pending"))
        val expected = listOf(TodoItem("write tests", TodoList.IN_PROGRESS), TodoItem("ship", TodoList.PENDING))
        assertEquals(expected, TodoList.parse(args(array)).items)
        assertEquals(expected, TodoList.parse(args(array.toString())).items)
    }

    @Test
    fun `the spellings models send are mapped onto three states`() {
        assertEquals(TodoList.DONE, TodoList.normalizeStatus("completed"))
        assertEquals(TodoList.DONE, TodoList.normalizeStatus(" Done "))
        assertEquals(TodoList.IN_PROGRESS, TodoList.normalizeStatus("in-progress"))
        assertEquals(TodoList.IN_PROGRESS, TodoList.normalizeStatus("In Progress"))
        assertEquals(TodoList.PENDING, TodoList.normalizeStatus(""))
        assertEquals(TodoList.PENDING, TodoList.normalizeStatus(null))
        assertNull(TodoList.normalizeStatus("cancelled"))
    }

    @Test
    fun `a bare string is a pending item and blank items are dropped`() {
        val array = JSONArray().put("read the code").put("   ").put(item("", "done"))
        assertEquals(listOf(TodoItem("read the code", TodoList.PENDING)), TodoList.parse(args(array)).items)
    }

    @Test
    fun `an unknown status is an error and names the item`() {
        val parsed = TodoList.parse(args(JSONArray().put(item("deploy", "blocked"))))
        val error = parsed.error
        assertNotNull(error)
        assertTrue(error.orEmpty().contains("blocked"))
        assertTrue(error.orEmpty().contains("deploy"))
        assertTrue(parsed.items.isEmpty())
    }

    @Test
    fun `missing todos and broken JSON are errors, an empty list is not`() {
        assertNotNull(TodoList.parse(args(null)).error)
        assertNotNull(TodoList.parse(args("[{")).error)
        val empty = TodoList.parse(args(JSONArray()))
        assertNull(empty.error)
        assertTrue(empty.items.isEmpty())
    }

    @Test
    fun `too many items is refused and long text is cut`() {
        val many = JSONArray()
        for (i in 0..TodoList.MAX_ITEMS) many.put(item("step $i", "pending"))
        assertNotNull(TodoList.parse(args(many)).error)

        val long = TodoList.parse(args(JSONArray().put(item("x".repeat(500), "pending")))).items.single()
        assertEquals(TodoList.MAX_CONTENT_CHARS, long.content.length)
    }

    @Test
    fun `the result reads the list back with its progress`() {
        val items = listOf(
            TodoItem("a", TodoList.DONE),
            TodoItem("b", TodoList.IN_PROGRESS),
            TodoItem("c", TodoList.PENDING),
        )
        assertEquals("Todo list updated: 1 of 3 done.\n[x] a\n[>] b\n[ ] c", TodoList.render(items))
        assertEquals("Todo list cleared.", TodoList.render(emptyList()))
        assertTrue(TodoList.render(listOf(TodoItem("a", TodoList.DONE))).contains("finished"))
    }

    @Test
    fun `the card headline is the step in hand, then the next one, then nothing`() {
        val a = TodoItem("a", TodoList.DONE)
        val b = TodoItem("b", TodoList.PENDING)
        val c = TodoItem("c", TodoList.IN_PROGRESS)
        assertEquals(c, TodoList.headline(listOf(a, b, c)))
        assertEquals(b, TodoList.headline(listOf(a, b)))
        assertNull(TodoList.headline(listOf(a)))
    }

    @Test
    fun `a stored list round-trips and a damaged one reads as empty`() {
        val items = listOf(TodoItem("a \"quoted\" step", TodoList.DONE), TodoItem("b", TodoList.PENDING))
        assertEquals(items, TodoList.decode(TodoList.encode(items)))
        assertTrue(TodoList.decode("not json").isEmpty())
        assertTrue(TodoList.decode(null).isEmpty())
    }

    @Test
    fun `the tool replaces the conversation's list and a bad call leaves it alone`() {
        val session = "todo-test-" + System.nanoTime()
        val first = TodoWriteTool.execute(
            args(JSONArray().put(item("one", "in_progress")).put(item("two", "pending"))).toString(),
            session,
            null,
            false,
        )
        assertTrue(first.output, first.success)
        assertEquals(2, TodoStore.get(session).size)
        assertEquals(TodoStore.get(session), TodoStore.lists.value[session])

        val bad = TodoWriteTool.execute(args(JSONArray().put(item("one", "nope"))).toString(), session, null, false)
        assertFalse(bad.success)
        assertEquals(2, TodoStore.get(session).size)

        val second = TodoWriteTool.execute(args(JSONArray().put(item("one", "done"))).toString(), session, null, false)
        assertTrue(second.success)
        assertEquals(listOf(TodoItem("one", TodoList.DONE)), TodoStore.get(session))

        assertTrue(TodoWriteTool.execute(args("[]").toString(), session, null, false).success)
        assertTrue(TodoStore.get(session).isEmpty())
    }

    // ---------------------------------------------------------- source guards

    @Test
    fun `todo_write is offered, dispatched and shown`() {
        assertTrue(AgentTools.makeAgentTools().any { it.name == TodoWriteTool.NAME })
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val at = vm.indexOf("TodoWriteTool.execute(")
        assertTrue("the dispatcher must route todo_write", at > 0)
        assertTrue(
            "a sub agent's list must not be written to disk",
            vm.substring(at, at + 300).contains("persist = helperConfig == null"),
        )
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("SessionTodoCard(sessionId = { viewModel.currentSessionId })"))
    }
}
