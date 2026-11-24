package com.openminis.app.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** [T-coding-m2-todo] One line of the agent's task list. */
data class TodoItem(val content: String, val status: String)

/**
 * [T-coding-m2-todo] The rules of the task list: what a valid list is, how it
 * reads back to the model, and how it is stored. No Android types, so all of it
 * is unit-tested on the JVM.
 */
object TodoList {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val DONE = "done"

    const val MAX_ITEMS = 30
    const val MAX_CONTENT_CHARS = 200

    data class Parsed(val items: List<TodoItem>, val error: String? = null)

    /** Maps the spellings models actually send onto the three states; null if unknown. */
    fun normalizeStatus(raw: String?): String? {
        val key = raw.orEmpty().trim().lowercase().replace('-', '_').replace(' ', '_')
        return when (key) {
            "", "pending", "todo", "not_started", "open" -> PENDING
            "in_progress", "inprogress", "doing", "active", "started", "running" -> IN_PROGRESS
            "done", "completed", "complete", "finished" -> DONE
            else -> null
        }
    }

    /**
     * Reads `todos` from the tool arguments. It may arrive as a JSON array or as
     * a string holding one (the same two shapes user_ask accepts), and
     * an item may be an object or a bare string.
     */
    fun parse(args: JSONObject): Parsed {
        val raw: Any? = if (args.has("todos") && !args.isNull("todos")) args.opt("todos") else null
        val array: JSONArray = when (raw) {
            null -> return Parsed(emptyList(), "Error: `todos` is required. Send the full list as a JSON array; send [] to clear it.")
            is JSONArray -> raw
            is JSONObject -> JSONArray().put(raw)
            is String -> if (raw.isBlank()) {
                JSONArray()
            } else {
                try {
                    JSONArray(raw)
                } catch (e: Exception) {
                    return Parsed(emptyList(), "Error: `todos` is not a valid JSON array: ${e.message}")
                }
            }
            else -> return Parsed(emptyList(), "Error: `todos` must be a JSON array of {content, status} objects.")
        }

        val items = ArrayList<TodoItem>()
        for (i in 0 until array.length()) {
            val entry: Any? = array.opt(i)
            val content: String
            val statusRaw: String?
            if (entry is JSONObject) {
                content = firstText(entry, "content", "text", "task", "title")
                statusRaw = if (entry.has("status") && !entry.isNull("status")) entry.optString("status", "") else ""
            } else if (entry is String) {
                content = entry
                statusRaw = ""
            } else {
                continue
            }
            val text = content.trim().replace('\n', ' ').replace('\r', ' ')
            if (text.isEmpty()) continue
            val status = normalizeStatus(statusRaw)
                ?: return Parsed(
                    emptyList(),
                    "Error: unknown status \"$statusRaw\" for \"${text.take(60)}\". Use pending, in_progress or done. " +
                        "The list was NOT changed. Leave an item out to drop it.",
                )
            items.add(TodoItem(if (text.length > MAX_CONTENT_CHARS) text.take(MAX_CONTENT_CHARS - 1) + "…" else text, status))
        }
        if (items.size > MAX_ITEMS) {
            return Parsed(
                emptyList(),
                "Error: ${items.size} items is too many (limit $MAX_ITEMS). The list was NOT changed. Group small steps together.",
            )
        }
        return Parsed(items)
    }

    private fun firstText(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.optString(k, "")
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    fun doneCount(items: List<TodoItem>): Int = items.count { it.status == DONE }

    /** What the collapsed card shows: the step in hand, else the next one, else null (all done). */
    fun headline(items: List<TodoItem>): TodoItem? =
        items.firstOrNull { it.status == IN_PROGRESS } ?: items.firstOrNull { it.status == PENDING }

    fun marker(status: String): String = when (status) {
        DONE -> "[x]"
        IN_PROGRESS -> "[>]"
        else -> "[ ]"
    }

    /** The tool result: the list as it now stands, so the model never has to remember it. */
    fun render(items: List<TodoItem>): String {
        if (items.isEmpty()) return "Todo list cleared."
        val done = doneCount(items)
        val sb = StringBuilder()
        sb.append("Todo list updated: ").append(done).append(" of ").append(items.size).append(" done.")
        if (done == items.size) sb.append(" Everything on it is finished.")
        for (item in items) {
            sb.append('\n').append(marker(item.status)).append(' ').append(item.content)
        }
        return sb.toString()
    }

    fun encode(items: List<TodoItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().put("content", item.content).put("status", item.status))
        }
        return array.toString()
    }

    /** Tolerant on purpose: a damaged stored list reads as empty, never as a crash. */
    fun decode(stored: String?): List<TodoItem> {
        if (stored.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(stored)
            val items = ArrayList<TodoItem>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val content = obj.optString("content", "")
                val status = normalizeStatus(obj.optString("status", "")) ?: continue
                if (content.isNotBlank()) items.add(TodoItem(content, status))
            }
            items
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * [T-coding-m2-todo] The task list of each conversation, held in memory for the
 * card above the composer and mirrored to SharedPreferences so it survives the
 * app being closed. One preference per conversation, removed when its list is
 * emptied.
 */
object TodoStore {
    const val PREFS = "session_todos"

    private val _lists = MutableStateFlow<Map<String, List<TodoItem>>>(emptyMap())
    val lists: StateFlow<Map<String, List<TodoItem>>> = _lists.asStateFlow()

    fun get(sessionId: String): List<TodoItem> = _lists.value[sessionId].orEmpty()

    /**
     * Replace a conversation's list. [persist] is false for a sub agent: its
     * list lives only as long as its run, and [context] may then be null.
     */
    fun set(context: Context?, sessionId: String, items: List<TodoItem>, persist: Boolean) {
        if (sessionId.isEmpty()) return
        _lists.update { current -> current + (sessionId to items) }
        if (!persist || context == null) return
        try {
            val editor = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (items.isEmpty()) editor.remove(sessionId) else editor.putString(sessionId, TodoList.encode(items))
            editor.apply()
        } catch (_: Exception) {
            // The card still shows the list for this run; only the restart copy is lost.
        }
    }

    /** Bring a stored list back into memory, unless this run already has one. */
    fun ensureLoaded(context: Context, sessionId: String) {
        if (sessionId.isEmpty() || _lists.value.containsKey(sessionId)) return
        val stored = try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sessionId, null)
        } catch (_: Exception) {
            null
        }
        val items = TodoList.decode(stored)
        _lists.update { current -> if (current.containsKey(sessionId)) current else current + (sessionId to items) }
    }
}
