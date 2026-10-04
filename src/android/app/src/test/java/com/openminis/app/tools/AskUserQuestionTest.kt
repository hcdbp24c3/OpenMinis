package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-ask-user] The ask tool's data half: what the model is allowed to ask,
 * and what the user's answer looks like when it goes back.
 *
 * The reason this is pinned tightly: a question that parses into "nothing" is
 * silently dropped, and the model then receives "no valid questions" instead of an
 * answer — so the shapes a model actually emits (an array, a JSON string, one
 * object, an option list that is too short) all have to land where the sheet and
 * the model expect.
 */
class AskUserQuestionTest {

    private fun option(label: String, description: String = "") =
        JSONObject().apply {
            put("label", label)
            if (description.isNotEmpty()) put("description", description)
        }

    private fun question(
        text: String,
        options: List<JSONObject>,
        header: String = "",
        multi: Boolean = false,
    ) = JSONObject().apply {
        put("question", text)
        if (header.isNotEmpty()) put("header", header)
        put("options", JSONArray(options))
        if (multi) put("multiSelect", true)
    }

    @Test
    fun `parses a questions array with headers, descriptions and multiSelect`() {
        val params = JSONObject().put(
            "questions",
            JSONArray()
                .put(
                    question(
                        "Which storage backend should the backup use?",
                        listOf(option("S3", "object storage"), option("WebDAV")),
                        header = "Backup location",
                    ),
                )
                .put(
                    question("Which providers should be enabled?", listOf(option("A"), option("B")), multi = true),
                ),
        )
        val parsed = AskUserQuestion.parse(params)
        assertEquals(2, parsed.size)
        assertEquals("Backup location", parsed[0].header)
        assertEquals(2, parsed[0].options.size)
        assertEquals("object storage", parsed[0].options[0].description)
        assertTrue(parsed[0].multiSelect.not())
        assertTrue(parsed[1].multiSelect)
    }

    @Test
    fun `accepts a JSON string and a single object as well as an array`() {
        val asString = JSONObject().put(
            "questions",
            """[{"question":"Pick one","options":[{"label":"A"},{"label":"B"}]}]""",
        )
        assertEquals(1, AskUserQuestion.parse(asString).size)

        val single = JSONObject().put(
            "questions",
            question("Pick one", listOf(option("A"), option("B"))),
        )
        assertEquals(1, AskUserQuestion.parse(single).size)
    }

    @Test
    fun `drops questions a sheet could not render`() {
        val params = JSONObject().put(
            "questions",
            JSONArray()
                // No question text.
                .put(question("   ", listOf(option("A"), option("B"))))
                // A single option is not a choice.
                .put(question("Only one?", listOf(option("A"))))
                // No options at all.
                .put(question("Nothing to pick?", emptyList()))
                .put(question("Valid?", listOf(option("A"), option("B")))),
        )
        val parsed = AskUserQuestion.parse(params)
        assertEquals(1, parsed.size)
        assertEquals("Valid?", parsed.single().question)
        // The model is told why, rather than being handed an empty answer.
        assertTrue(AskUserQuestion.parse(JSONObject()).isEmpty())
    }

    @Test
    fun `caps the number of questions and options per question`() {
        val many = (1..6).map { index ->
            question(
                "Q$index",
                (1..6).map { option("opt$it") },
            )
        }
        val parsed = AskUserQuestion.parse(JSONObject().put("questions", JSONArray(many)))
        assertEquals("at most 4 questions reach the sheet", 4, parsed.size)
        assertEquals("at most 4 options reach the sheet", 4, parsed[0].options.size)
    }

    @Test
    fun `formatAnswers mirrors the questions with what was picked`() {
        val questions = AskUserQuestion.parse(
            JSONObject().put(
                "questions",
                JSONArray()
                    .put(question("Which backend?", listOf(option("S3"), option("WebDAV")), header = "Backend"))
                    .put(question("Which providers?", listOf(option("A"), option("B"), option("C")), multi = true)),
            ),
        )
        val json = AskUserQuestion.formatAnswers(questions, listOf(listOf("S3"), listOf("A", "C")))
        val answers = JSONObject(json).getJSONArray("answers")
        assertEquals(2, answers.length())
        assertEquals("Backend", answers.getJSONObject(0).getString("header"))
        assertEquals("S3", answers.getJSONObject(0).getJSONArray("answers").getString(0))
        assertEquals(2, answers.getJSONObject(1).getJSONArray("answers").length())
    }

    @Test
    fun `a free-text answer travels as a single-element answers list`() {
        val questions = AskUserQuestion.parse(
            JSONObject().put(
                "questions",
                JSONArray().put(question("Where?", listOf(option("Home"), option("Work")))),
            ),
        )
        // The sheet sends the typed text instead of a chip label; both are just
        // answers to the model, which is why the free-text path needs no special
        // shape here.
        val json = AskUserQuestion.formatAnswers(questions, listOf(listOf("Somewhere else entirely")))
        assertEquals(
            "Somewhere else entirely",
            JSONObject(json).getJSONArray("answers").getJSONObject(0)
                .getJSONArray("answers").getString(0),
        )
    }

    @Test
    fun `a question with no selection reports an empty answers list`() {
        val questions = AskUserQuestion.parse(
            JSONObject().put(
                "questions",
                JSONArray().put(question("Pick?", listOf(option("A"), option("B")))),
            ),
        )
        val json = AskUserQuestion.formatAnswers(questions, listOf(emptyList()))
        assertEquals(
            0,
            JSONObject(json).getJSONArray("answers").getJSONObject(0).getJSONArray("answers").length(),
        )
    }

    @Test
    fun `definition keeps the MCP name that models already know`() {
        assertEquals("ask_user_question", AskUserQuestion.NAME)
        assertEquals("AskUserQuestion", AskUserQuestion.ALIAS)
        val definition = AskUserQuestion.definition()
        assertTrue(definition.required.contains("questions"))
        // The description has to carry the "don't ask about routine tool calls"
        // rule: without it models ask permission for every shell command.
        assertTrue(definition.description.contains("Do NOT use this to ask permission"))
    }
}
