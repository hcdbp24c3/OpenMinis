package com.openminis.app.agent

/**
 * [T-coding-m5-commands] Slash commands that are prompts, not actions.
 *
 * Tapping one in the "/" menu puts its prompt into the message box; the user can
 * edit it and sends it like any message. Nothing runs by itself, so none of them
 * needs its own approval path: the turn that follows goes through the same tools,
 * plan mode and shell approval as every other turn.
 */
object CodingCommands {

    data class Command(val id: String, val title: String, val subtitle: String, val prompt: String)

    val ALL: List<Command> = listOf(
        Command(
            id = "init",
            title = "Init",
            subtitle = "Write or refresh AGENTS.md for this project",
            prompt = "Study this project and write an AGENTS.md at its root for future coding sessions. " +
                "Read the build files, the README and the layout first. Keep it under 60 lines and include only what you verified: " +
                "what the project is, how to build it, how to run all tests and a single test, lint or format commands, " +
                "the directory layout in a few lines, and conventions a newcomer would get wrong. " +
                "If an AGENTS.md or CLAUDE.md already exists, improve it in place instead of replacing what the maintainers wrote. " +
                "Do not invent commands; mark anything you could not run as unverified.",
        ),
        Command(
            id = "review",
            title = "Review",
            subtitle = "Review the current changes and list findings",
            prompt = "Review the changes in this project (use turn_changes if available, otherwise git status and git diff). " +
                "Read the surrounding code, not only the diff. Report findings worst first, each with a priority " +
                "(P0 breaks things, P1 should fix, P2 worth fixing, P3 nit), the file and line, what goes wrong and with which input. " +
                "Cover correctness, error handling, security and missing tests; skip style opinions. " +
                "End with a one-line verdict: correct, or not correct and why. Do not change any file.",
        ),
        Command(
            id = "test",
            title = "Test",
            subtitle = "Run the project's checks and report the result",
            prompt = "Find how this project is checked (build file, test task, package scripts, CI config) and run the checks " +
                "that are affordable on this device, narrowest first. Report each exact command, its exit status, the pass and fail " +
                "counts and the names of failing tests with the key output. Say what you did not run and why. " +
                "Do not edit code or tests to make them pass; if a failure has an obvious cause, describe the fix instead.",
        ),
        Command(
            id = "commit",
            title = "Commit",
            subtitle = "Propose a commit for the current changes",
            prompt = "Prepare a commit for the current changes. Show git status and a short summary of the diff, " +
                "leave out files that look like secrets, build output or unrelated edits, and propose a message in the style of " +
                "this repository's recent history (subject of at most 72 characters, then why the change was made). " +
                "Ask me to confirm the file list and the message before you run git commit. Do not push, amend or rewrite history.",
        ),
    )

    private val byId: Map<String, Command> = ALL.associateBy { it.id }

    /** The prompt behind a command id, or null when the id is not a prompt command. */
    fun promptFor(id: String): String? = byId[id]?.prompt

    fun subtitleFor(id: String): String? = byId[id]?.subtitle

    /** Text for the message box: the prompt, with whatever the user had typed kept below it. */
    fun composerText(id: String, existing: String?): String? {
        val prompt = promptFor(id) ?: return null
        val kept = existing?.trim().orEmpty()
        return if (kept.isEmpty()) prompt else prompt + "\n\n" + kept
    }
}
