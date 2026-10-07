package com.openminis.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * [T-sub-agents-v1] Hard limits on the Sub Agent roster.
 *
 * Port of iOS `SubAgentLimits` (Providers/SubAgentDefinition.swift).
 *
 * These are not defensive "just in case" numbers: the roster is injected into
 * the main conversation's system prompt on every turn, so the count and the
 * description length ARE the fixed per-request cost. Bounding the input is what
 * removes the need for a runtime token warning — a user cannot configure a
 * roster that blows the budget. At the maximum (10 x (40 + 200) chars) the
 * roster is ~800 tokens.
 *
 * Shared by the editor UI, the load-time clamp and the roster generator, so the
 * three cannot drift apart.
 */
object SubAgentLimits {
    /**
     * [T-sub-agent-builtin-roster] Custom (user-created) agents a roster may hold.
     *
     * This is the number that must NOT move when built-ins are added. It replaces a
     * single `MAX_COUNT = 10` that was written when exactly one built-in shipped, and
     * which the extra built-ins then silently ate into: the bound was applied to
     * `MAX_COUNT - builtIns.size`, so a user who had nine custom agents before an
     * upgrade would have lost six of them to a roster rule they never touched. A
     * roster that was legal before an upgrade stays legal after it.
     */
    const val MAX_CUSTOM = 9

    /**
     * Ceiling on the whole roster the model reads (built-ins + [MAX_CUSTOM]).
     *
     * Built-ins DO count towards the prompt: the roster is injected every turn, so the
     * absolute size of it is the thing actually being bounded here. This ceiling exists
     * for a future build that ships many more built-ins — it caps that growth without
     * ever touching the user's own allowance.
     */
    const val MAX_TOTAL = 16

    const val NAME_MAX_LENGTH = 40

    /** The only free text that reaches the main conversation. */
    const val DESCRIPTION_MAX_LENGTH = 200

    /** Child-session only; bounded so one definition cannot eat the child's context. */
    const val INSTRUCTIONS_MAX_LENGTH = 4000
}

/**
 * [T-sub-agents-v1] One named sub agent the main model can delegate to by name.
 *
 * Port of iOS `SubAgentDefinition`. Field names are the iOS wire names verbatim
 * — this rides the existing provider-config blob, which both platforms read.
 *
 * Replaces the old anonymous delegation, where a child's whole identity was the
 * free-text `task` string and its model was a binary primary/sub tier. A
 * definition now carries the identity (name, description, instructions) and the
 * model (a pinned group, or Auto).
 */
@Serializable
data class SubAgentDefinition(
    val id: String = UUID.randomUUID().toString(),
    /**
     * The wire identifier, NOT a label.
     *
     * This is the `enum` of `subagent_task.agent`, the value the model has to
     * emit, and the key [SubAgentRoster.resolve] matches on. It is deliberately
     * never localized: localizing it would put the UI language into the tool
     * schema, make the model emit non-ASCII identifiers, and — worse — break
     * every stored reference the moment the user switched language or synced to
     * a device set to another one. [displayName] localizes the built-in for
     * presentation only.
     */
    var name: String,
    /**
     * What the main model reads to decide whether to pick this agent. Bounded
     * because it is the only part that costs main-conversation tokens, and not
     * localized for the same reason as [name] — the model is not the user.
     */
    var description: String,
    /** Appended to the child session's brief. Empty = nothing appended. */
    var instructions: String = "",
    /** null = Auto: the delegating model chooses with `model_choice`. */
    var modelGroupId: String? = null,
    /**
     * [T-subagent-thinking-override] Reasoning intensity for runs of this sub
     * agent, overriding whatever the resolved model group defaults to.
     *
     * null = inherit (the group's `defaultThinkingLevel`, else the delegating
     * conversation) — the existing behaviour, and what every definition has
     * until the user sets one. Mirrors [ModelGroup.defaultThinkingLevel]: a
     * group sets the default for sessions bound to it, and this overrides that
     * for this sub agent, the same way a session-level pick overrides a group.
     */
    var thinkingLevelOverride: ThinkingLevel? = null,
    val isBuiltIn: Boolean = false,
    var sortOrder: Int = 0,
    /**
     * [T-android-provider-iso8601-wire] Epoch millis in memory, but
     * serialized as an ISO-8601 string on the wire (see
     * [com.openminis.app.backup.Iso8601MillisSerializer]). iOS
     * `SubAgentDefinition.updatedAt` is a `Date`, encoded with
     * `.iso8601` in the backup exporter — a bare Long here made a restore
     * of an iOS-produced provider_config.json fail JSON decoding entirely
     * (kotlinx.serialization aborts the whole document on one bad element),
     * dragging every provider/model/sub-agent in the same file down to
     * imported=0. The serializer still accepts a legacy plain numeric epoch
     * for old Android-written packages.
     */
    @kotlinx.serialization.Serializable(with = com.openminis.app.backup.Iso8601MillisSerializer::class)
    var updatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * [T-sub-agent-builtin-roster] Localized label for the settings list and editor.
     *
     * ONLY the built-ins have one — a user-created agent is shown exactly as the user
     * named it. This is presentation only: [name] stays canonical English because it is
     * the tool-schema enum value the model must emit, and a device set to another
     * language must not change what the model has to say.
     */
    fun displayName(context: android.content.Context): String {
        val res = builtInLabelRes(name, description = false) ?: return name
        return context.getString(res)
    }

    /** Localized description for the settings screen, mirroring [displayName]. */
    fun displayDescription(context: android.content.Context): String {
        val res = builtInLabelRes(name, description = true) ?: return description
        return context.getString(res)
    }

    /**
     * Label resource for a built-in, keyed by its canonical English [name] — the same
     * rule iOS uses (`isBuiltIn ? AppLocalized(<canonical name>) : name`). Null for a
     * user-created agent, or for a built-in this build does not know (a roster synced
     * from a newer one): both fall back to the stored text.
     */
    private fun builtInLabelRes(name: String, description: Boolean): Int? =
        when (name) {
            BUILT_IN_NAME -> if (description) {
                com.openminis.app.R.string.sub_agent_builtin_description
            } else {
                com.openminis.app.R.string.sub_agent_builtin_name
            }
            SCOUT_NAME -> if (description) {
                com.openminis.app.R.string.sub_agent_builtin_scout_description
            } else {
                com.openminis.app.R.string.sub_agent_builtin_scout_name
            }
            REVIEWER_NAME -> if (description) {
                com.openminis.app.R.string.sub_agent_builtin_reviewer_description
            } else {
                com.openminis.app.R.string.sub_agent_builtin_reviewer_name
            }
            RESEARCHER_NAME -> if (description) {
                com.openminis.app.R.string.sub_agent_builtin_researcher_description
            } else {
                com.openminis.app.R.string.sub_agent_builtin_researcher_name
            }
            DEBUGGER_NAME -> if (description) {
                com.openminis.app.R.string.sub_agent_builtin_debugger_description
            } else {
                com.openminis.app.R.string.sub_agent_builtin_debugger_name
            }
            ARCHITECT_NAME -> if (description) {
                com.openminis.app.R.string.sub_agent_builtin_architect_description
            } else {
                com.openminis.app.R.string.sub_agent_builtin_architect_name
            }
            TESTER_NAME -> if (description) {
                com.openminis.app.R.string.sub_agent_builtin_tester_description
            } else {
                com.openminis.app.R.string.sub_agent_builtin_tester_name
            }
            else -> null
        }

    /**
     * Field-level clamp applied on load and on save.
     *
     * Synced data can come from a future build or a hand-edited file, so the UI
     * limits alone are not enough — see [SubAgentRoster.normalize].
     */
    fun clamped(): SubAgentDefinition = copy(
        name = name.take(SubAgentLimits.NAME_MAX_LENGTH),
        description = description.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH),
        instructions = instructions.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH),
    )

    /** Whether any field was over its limit — used only to decide whether to log. */
    val exceedsLimits: Boolean
        get() = name.length > SubAgentLimits.NAME_MAX_LENGTH ||
            description.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH ||
            instructions.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH

    companion object {
        /**
         * The built-in definition's fixed id. Its display name is localizable
         * and user-editable; the id is what code and persisted payloads key on.
         */
        const val BUILT_IN_ID = "builtin.general"

        /**
         * [T-sub-agents-v1] The sub agent tool's wire name.
         *
         * Renamed from `delegate_task` so it reads as "the sub agent tool"
         * rather than a generic verb. The separate `agent_status` tool is
         * folded in as an `action`, so the model sees ONE tool for delegating
         * and for inspecting or stopping what it delegated.
         */
        const val TOOL_NAME = "subagent_task"

        /** The built-in's stored name, deliberately NOT localized. See [name]. */
        const val BUILT_IN_NAME = "General Sub Agent"

        /** Also not localized: this goes into the system-prompt roster. */
        const val BUILT_IN_DESCRIPTION =
            "Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, " +
                "digesting bulk output into a conclusion, or running independent branches in parallel."

        /**
         * [T-sub-agent-builtin-roster] Three more built-ins beside the general one,
         * one per SHAPE of delegated work: find, judge, verify.
         *
         * They ship because a sub agent's value is largely in what it is told to do,
         * and the three shapes below are the ones the delegation prompt keeps
         * describing by hand ("search the repo and report, do not edit"; "review this
         * change"; "run the tests and tell me what happened"). A user can still write
         * their own — and often should, for a domain-specific reviewer — but the
         * common three no longer have to be typed out from scratch.
         *
         * Cost: the roster is injected into every turn's system prompt, so each entry
         * is name + description (bounded by [SubAgentLimits]) and nothing else —
         * instructions go to the child session only, and stay empty here so the user's
         * own wording is not being second-guessed by a default they did not write.
         */
        const val SCOUT_ID = "builtin.scout"
        const val SCOUT_NAME = "Recon Sub Agent"
        const val SCOUT_DESCRIPTION =
            "Read-only reconnaissance: map a codebase, a repository or a set of pages and return a compact " +
                "brief — where things live, how they connect, and what to read next. Told not to edit."

        const val REVIEWER_ID = "builtin.reviewer"
        const val REVIEWER_NAME = "Code Review Sub Agent"
        const val REVIEWER_DESCRIPTION =
            "Review a change or a file for correctness, security and test coverage, and report concrete " +
                "findings with the file and line. Told not to fix: the finding is the deliverable."

        const val TESTER_ID = "builtin.tester"
        const val TESTER_NAME = "Test Runner Sub Agent"
        const val TESTER_DESCRIPTION =
            "Verify a change by running the project's build and tests in the sandbox, then report the exact " +
                "command, its output and its exit status — green or red, with the evidence."

        /**
         * [T-sub-agent-builtin-roster] Three more built-ins, taken from what the
         * published sub-agent catalogs converge on (VoltAgent's awesome-claude-code-
         * subagents, jurabek/sub-agents): the roles those collections list most and
         * that are genuinely distinct shapes of work rather than domains.
         *
         *  - research (their `search-specialist`): answer from OUTSIDE sources, with
         *    URLs and cross-checking. [SCOUT_ID] maps a codebase; this one goes out to
         *    the web and is accountable for its sources.
         *  - debug (their `debugger`): find the cause of a failure that already
         *    happened. [TESTER_ID] verifies a change that already exists; this one
         *    establishes a fact that does not — what is actually broken and why.
         *  - architect (their `architect-review`): decide the shape of a change before
         *    code is written. [REVIEWER_ID] judges code that exists; this one judges a
         *    decision that does not.
         *
         * Their prompts are 5-10k characters of capability lists. These are deliberately
         * not: the value is the PROCESS and the output CONTRACT, and a child session
         * that is handed a wall of buzzwords spends its context on the wall. Each brief
         * below is one screen, names the tools this app actually has, and states what
         * the child must hand back.
         */
        const val RESEARCHER_ID = "builtin.researcher"
        const val RESEARCHER_NAME = "Research Sub Agent"
        const val RESEARCHER_DESCRIPTION =
            "Answer a question from OUTSIDE sources: run several search queries, read the pages that matter, " +
                "cross-check what they claim, and report it with the URLs. Says when sources disagree."

        const val DEBUGGER_ID = "builtin.debugger"
        const val DEBUGGER_NAME = "Debugger Sub Agent"
        const val DEBUGGER_DESCRIPTION =
            "Find the CAUSE of a failure: reproduce it, shrink it to the smallest case, test one hypothesis " +
                "at a time, and report the root cause with the evidence. Told not to fix."

        const val ARCHITECT_ID = "builtin.architect"
        const val ARCHITECT_NAME = "Architect Sub Agent"
        const val ARCHITECT_DESCRIPTION =
            "Decide how a change should be built before any code exists: read what is already there, weigh " +
                "two or three options, and return a plan with its tradeoffs and unknowns."

        /** The research brief: process, rules, and what must come back. */
        private const val RESEARCHER_INSTRUCTIONS = """You research a question from sources OUTSIDE this machine. The parent keeps your conclusion and discards your reading, so the conclusion has to stand on its own.

Method
- Turn the question into 3-5 queries instead of one: quote a phrase for exact wording, add a negative term to drop the noise, and try a variant in the language the best source is likely written in.
- web_search first for breadth. Then web_fetch the two or three results that look authoritative: primary sources (official docs, specs, release notes, the project's own repository) beat aggregators and SEO pages.
- Use browser_use only when a page needs JavaScript or a click before its content exists, and repo_digest when the answer lives in source rather than prose.
- A claim you only saw in a search-result summary is not evidence. Read the page.

Rules
- Quote the exact sentence for anything load-bearing, and give its URL.
- When sources disagree, say so, say which you find more credible, and why. Do not average them into a confident middle.
- Date what you cite. "Current" advice from a 2019 page is a different claim from a 2025 one; say which you have.
- Never invent a URL, a version number, or a quote. If you could not verify something, mark it unverified in one line rather than dropping the gap.
- You do not modify files or the repository, and nothing you do changes the system. If the finding implies a change, describe it; do not attempt it.

Report
1. The answer in a short paragraph, then the detail that supports it.
2. Findings as a list: claim -> evidence -> source URL.
3. What you could not establish, and what you would search next.
4. The queries you used, listed compactly."""

        /** The debug brief. */
        private const val DEBUGGER_INSTRUCTIONS = """You find the cause of something that is broken. The parent gets your conclusion, not your session, so a precise cause beats a long transcript.

Method
- Establish the failure as a FACT first: the exact command, its exact output, its exit status. A failure you cannot reproduce is a hypothesis, not a finding, and you must say which one you have.
- Shrink it: fewer inputs, fewer flags, a smaller file, a direct call instead of the whole pipeline. The smallest reproducing case is usually where the cause becomes obvious.
- Read the error rather than skimming it: the frame that raised, the value it says was wrong, and the state (null, empty, stale, wrong type) name the suspect.
- Check what changed: edits in the working tree, a version bump, a config value. A failure that appeared with a change is nearly always explained by that change.
- Form ONE hypothesis and test it: a print, an assertion, a one-line probe, a minimal script. Read the code path the failure actually takes, not the one you assume it takes. Instrumenting many places at once means you will not know which line mattered.
- Do not stop at the first plausible explanation. Name the mechanism — why this input produces this wrong output. "Probably a race" is not a cause.

Reporting
1. Root cause in one or two sentences, naming the file and line.
2. The evidence: the reproducing command and its output, plus the probe that proved it.
3. The smallest case that still fails, and the boundary where it stops.
4. Anything you changed while investigating, so the parent can revert it — leave the tree clean.
5. The fix you would make, described and not applied. The finding is the deliverable.
6. If you could not isolate it: what you ruled OUT, and the next experiment you would run."""

        /** The architect brief. */
        private const val ARCHITECT_INSTRUCTIONS = """You decide HOW something should be built or changed, before any code is written. The parent implements from your plan, so it has to be specific enough to follow and honest about what you do not know.

Method
- Read the code that already exists. A plan for a system you have not read is a guess about a system that may already solve the problem — find the existing pattern and extend it rather than inventing a parallel one.
- Name the constraints you are planning against: language and version, platform, the size of the change, what must keep working, and what the project already depends on.
- Give two or three options when the choice is real. For each: what it costs, what it makes easy, what it makes hard, and what it breaks. One option presented as obvious is usually a decision nobody examined.
- Recommend one and say why the others lost. Prefer the boring option: fewer moving parts, no new dependency, steps that can be undone.
- State the assumptions each part rests on, so the parent can check them instead of discovering them at the end.
- Size the blast radius: every file and caller the change touches, and what must be updated with it (tests, docs, serialized formats, migrations).

Reporting
1. The recommendation in a short paragraph.
2. The plan as ordered steps, each small enough to verify on its own.
3. Tradeoffs, per option: cost, gain, risk.
4. Assumptions and unknowns.
5. What would make you change the recommendation."""

        /**
         * [T-sub-agent-builtin-roster] Briefs for the original four built-ins.
         *
         * They shipped without one at first, on the theory that an empty default respects
         * the user's own wording. In practice an empty brief means the built-in is only a
         * NAME: the child session gets the same generic loop whichever one you pick, so
         * "Recon Sub Agent" and "General Sub Agent" behave identically. The brief is what
         * makes the name true. It is also free in the roster sense — instructions go to
         * the child session, never to the main conversation — and it stays editable.
         */
        private const val GENERAL_INSTRUCTIONS = """You take a self-contained task that needs its own tool loop. The parent keeps your conclusion and discards your session, so the conclusion has to stand alone.

How
- Work in the sandbox you were given: shell_execute for commands, the file tools for edits, web_search / web_fetch / browser_use for anything outside it, and repo_digest to read a repository without cloning it.
- Decide, then act. Read enough to be sure of the next step, but do not survey a whole project to answer a narrow question.
- Verify what you claim. A command's output and exit status are evidence; your expectation of what it would print is not.
- Stop when the task is done, and report it.

Reporting
1. What you did, in one short paragraph.
2. The result the parent asked for — the answer, the file changed, the command output — quoted rather than paraphrased wherever it matters.
3. Anything you could not do, and why: a missing tool, an absent permission, an ambiguous request.
4. Anything you changed on disk, and anything you left running (browser tabs, background jobs)."""

        private const val SCOUT_INSTRUCTIONS = """You map what is there and report it. Read-only: you inspect and report, you do not fix, edit, or "quickly" change a file.

How
- Search and glob first to find the files that matter, then read only those. Reading everything is slower and no more accurate.
- Answer with locations: file path and line, the symbol, and the line of code that proves it.
- Name the relationships: who calls this, what it depends on, which config value decides the behaviour. A list of files with no links between them is not a map.
- Say when something is NOT there — "no callers outside tests", "no config default". Those absences are findings, and usually the ones the parent needs.

Reporting
1. The answer to the question that was asked.
2. Where it lives: path and line per fact.
3. How the pieces connect.
4. What you looked for and did not find, and where else it could live."""

        private const val REVIEWER_INSTRUCTIONS = """You review a change or a file and report findings. You do not fix: the finding is the deliverable, and a fix applied without the parent knowing hides the problem.

How
- Read the change AND the code around it. A diff on its own hides the context that makes it wrong.
- Look for what survives review and still breaks in production, in this order: correctness (wrong result, unhandled case, off-by-one, wrong type); state and concurrency (stale value, race, missing cancellation); error paths (swallowed exception, missing cleanup, leak); interface contracts (a caller left behind, a serialized field renamed, a nullability change); and tests that no longer mean anything.
- Report a finding only with evidence: the file, the line, the input, and what happens. "Consider refactoring" is noise.
- Rank by severity, and say plainly when a change is fine. A review that always finds something is not a review.

Reporting
1. Verdict in one line: safe / safe with nits / needs work.
2. Findings, worst first: severity, file and line, what breaks, and the evidence.
3. What you checked and found clean, so the parent knows the coverage.
4. Questions you could not settle by reading."""

        private const val TESTER_INSTRUCTIONS = """You verify a change by running things. The parent wants evidence, not an opinion.

How
- Find the project's own entry points first (build file, test task, Makefile, package scripts) and use them: a hand-rolled command proves less than the one CI runs.
- Run the narrowest thing that exercises the change, then the wider suite. Quote the exact command and its exit status.
- Report the real numbers: N passed, M failed, and the failing names. "Tests pass" without a count is not a result.
- A failure you caused — wrong directory, missing dependency, dirty tree — is not a finding. Fix your command, and say that you did.
- Do not weaken a test to make it pass and do not skip one to make the run green. If a test is genuinely wrong, say so and show why, and leave it alone.
- Leave the tree as you found it: revert your experiments, do not commit, do not push.

Reporting
1. What you ran (exact commands) and the verdict.
2. The evidence: pass/fail counts, failing test names, key output quoted.
3. If it failed: whether the change or the environment is responsible, with the evidence for that call.
4. What you did NOT run, and why (no runner, missing SDK, too slow). Silence here reads as coverage."""

        /**
         * Every built-in id, in roster order. The order is the disclosure order, and
         * the first entry is what a blank agent name resolves to.
         */
        val BUILT_IN_IDS = listOf(
            BUILT_IN_ID, SCOUT_ID, REVIEWER_ID, TESTER_ID, RESEARCHER_ID, DEBUGGER_ID, ARCHITECT_ID,
        )

        /** Whether [id] belongs to a built-in this build knows. */
        fun isBuiltInId(id: String): Boolean = id in BUILT_IN_IDS

        /**
         * The built-in general sub agent, inserted by normalize when absent.
         *
         * Kept separate from [makeBuiltIns] because `BUILT_IN_ID` is long-standing
         * stored data and several call sites name it directly.
         */
        fun makeBuiltIn(sortOrder: Int = 0): SubAgentDefinition = SubAgentDefinition(
            id = BUILT_IN_ID,
            name = BUILT_IN_NAME,
            description = BUILT_IN_DESCRIPTION,
            instructions = "",
            modelGroupId = null,
            isBuiltIn = true,
            sortOrder = sortOrder,
        )

        /**
         * The canonical spec for every built-in, in roster order. [SubAgentRoster.normalize]
         * starts from this list and folds each stored row's user-owned fields back in,
         * which is what makes a roster written by an older build (only
         * [BUILT_IN_ID]) gain the new ones instead of losing them forever.
         */
        fun makeBuiltIns(): List<SubAgentDefinition> = listOf(
            makeBuiltIn(sortOrder = 0).copy(instructions = GENERAL_INSTRUCTIONS),
            SubAgentDefinition(
                id = SCOUT_ID,
                name = SCOUT_NAME,
                description = SCOUT_DESCRIPTION,
                instructions = SCOUT_INSTRUCTIONS,
                isBuiltIn = true,
                sortOrder = 1,
            ),
            SubAgentDefinition(
                id = REVIEWER_ID,
                name = REVIEWER_NAME,
                description = REVIEWER_DESCRIPTION,
                instructions = REVIEWER_INSTRUCTIONS,
                isBuiltIn = true,
                sortOrder = 2,
            ),
            SubAgentDefinition(
                id = TESTER_ID,
                name = TESTER_NAME,
                description = TESTER_DESCRIPTION,
                instructions = TESTER_INSTRUCTIONS,
                isBuiltIn = true,
                sortOrder = 3,
            ),
            SubAgentDefinition(
                id = RESEARCHER_ID,
                name = RESEARCHER_NAME,
                description = RESEARCHER_DESCRIPTION,
                instructions = RESEARCHER_INSTRUCTIONS,
                isBuiltIn = true,
                sortOrder = 4,
            ),
            SubAgentDefinition(
                id = DEBUGGER_ID,
                name = DEBUGGER_NAME,
                description = DEBUGGER_DESCRIPTION,
                instructions = DEBUGGER_INSTRUCTIONS,
                isBuiltIn = true,
                sortOrder = 5,
            ),
            SubAgentDefinition(
                id = ARCHITECT_ID,
                name = ARCHITECT_NAME,
                description = ARCHITECT_DESCRIPTION,
                instructions = ARCHITECT_INSTRUCTIONS,
                isBuiltIn = true,
                sortOrder = 6,
            ),
        )
    }
}

/**
 * [T-sub-agents-v1] Roster-level rules: the built-in must exist, the list is
 * bounded, ordering is the disclosure order.
 *
 * Free functions on the list rather than a store type, so the load path, the
 * sync merge and the tests all share one implementation.
 */
object SubAgentRoster {

    /**
     * Normalise a decoded roster: guarantee the built-in, clamp every field,
     * bound the count, and renumber [SubAgentDefinition.sortOrder] densely
     * from 0.
     *
     * NEVER throws and never drops the built-in: this runs on data that may
     * have arrived from a possibly newer build, and a bad roster must not be
     * able to block startup. Anything discarded is logged.
     */
    fun normalize(
        input: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): List<SubAgentDefinition> {
        val list = input.toMutableList()

        // [T-sub-agent-builtin-roster] Built-ins are matched by ID and taken from the
        // canonical spec, never from the stored row: their name and description ARE the
        // tool-schema enum and the roster the model reads, so they must not carry a
        // store's UI language or a newer build's wording. Spec order pins them to the
        // front, and a built-in this build has but the stored roster does not (the
        // upgrade path from a build that shipped only `builtin.general`) is added here
        // rather than lost.
        //
        // Everything the USER owns on a built-in — model group, instructions, thinking
        // override — is folded back in from the stored row.
        val byId = list.groupBy { it.id }
        val builtIns = SubAgentDefinition.makeBuiltIns().map { spec ->
            val row = byId[spec.id]?.firstOrNull()
            if (row == null) {
                log?.invoke("[SubAgents] built-in '${spec.id}' missing — reinserting")
                spec
            } else {
                if (row.name != spec.name || row.description != spec.description) {
                    log?.invoke("[SubAgents] restoring the built-in's canonical name/description")
                }
                spec.copy(
                    instructions = row.instructions,
                    modelGroupId = row.modelGroupId,
                    thinkingLevelOverride = row.thinkingLevelOverride,
                    updatedAt = row.updatedAt,
                )
            }
        }

        // Custom entries keep the user's order; ties break by id so the result
        // is deterministic across devices.
        var custom: List<SubAgentDefinition> =
            list.filterNot { SubAgentDefinition.isBuiltInId(it.id) }
        custom = custom.sortedWith(compareBy({ it.sortOrder }, { it.id }))

        // Drop anything past the CUSTOM allowance, which the built-ins do not eat into
        // (see SubAgentLimits.MAX_CUSTOM) — only the total roster is capped.
        val allowedCustom = minOf(
            SubAgentLimits.MAX_CUSTOM,
            maxOf(0, SubAgentLimits.MAX_TOTAL - builtIns.size),
        )
        if (custom.size > allowedCustom) {
            val dropped = custom.drop(allowedCustom).map { it.name }
            log?.invoke(
                "[SubAgents] roster over the limit — keeping $allowedCustom of ${custom.size} " +
                    "custom definitions, dropping: ${dropped.joinToString(", ")}",
            )
            custom = custom.take(allowedCustom)
        }

        if (builtIns.any { it.exceedsLimits } || custom.any { it.exceedsLimits }) {
            log?.invoke("[SubAgents] one or more definitions exceeded field limits — truncating")
        }

        val out = builtIns.mapIndexed { index, def -> def.clamped().copy(sortOrder = index) }.toMutableList()
        val seenNames = out.map { nameKey(it.name) }.toMutableSet()
        var next = out.size
        for (def in custom) {
            // A custom definition must not claim the built-in flag: isBuiltIn
            // drives "cannot delete" in the UI, and a synced row could assert
            // it. Its id cannot be a built-in one either — those were filtered
            // out above, so anything left asserting the flag is an impostor.
            if (def.isBuiltIn) continue
            // A nameless definition cannot be addressed: the name IS the enum
            // value the model emits and the key resolve() matches on. Left in,
            // it would advertise an empty string in the tool schema and put a
            // blank line in the roster the model reads. The settings screen
            // creates one the moment "Add" is tapped, so this is the ordinary
            // state of a row the user backed out of, not a corrupt one.
            if (def.name.isBlank()) continue
            // Two rows sharing a name make resolution ambiguous — first match
            // wins and the user cannot see why the other never runs. Seeded with
            // the built-in names, so a custom "Recon Sub Agent" is dropped too.
            if (!seenNames.add(nameKey(def.name))) continue
            out.add(def.clamped().copy(sortOrder = next))
            next += 1
        }
        return out
    }

    /**
     * The definition a delegation should run under.
     *
     * Matching is case-insensitive and whitespace-trimmed because the name
     * comes back from a model. A null/blank name = the built-in. A name that
     * matches nothing returns null, which the caller turns into
     * `unknown_agent`.
     */
    fun resolve(name: String?, roster: List<SubAgentDefinition>): SubAgentDefinition? {
        val raw = name?.let { nameKey(it) }
        if (raw.isNullOrEmpty()) {
            return roster.firstOrNull { it.id == SubAgentDefinition.BUILT_IN_ID } ?: roster.firstOrNull()
        }
        return roster.firstOrNull { nameKey(it.name) == raw }
    }

    /**
     * The comparison form of a name: trimmed, case-folded and diacritic-folded.
     *
     * The name comes back from a MODEL, which may not reproduce accents exactly,
     * and the stored side is user-typed and may carry stray whitespace. Folding
     * both is what stops a roster entry named " Résumé-agent " from resolving on
     * one platform and failing with `unknown_agent` on the other. Matches iOS
     * SubAgentDefinition.nameKey.
     */
    fun nameKey(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()

    /** Hoisted: the merge path calls [nameKey] O(local × remote) times. */
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Outcome of [mergeBackup]: the roster to save plus the report counts. */
    data class BackupMerge(val roster: List<SubAgentDefinition>, val written: Int, val skipped: Int)

    /**
     * [T-android-backup-subagents] Fold a backup package's custom sub agents
     * into the local roster, with iOS's restore rules
     * (BackupImporter+Categories importSubAgents -> SubAgentRoster.merge):
     *  - an id not known locally is added, unless its NAME matches a local
     *    agent: the model picks agents by name, so two entries it cannot tell
     *    apart would make one unreachable; the local one stays;
     *  - a known id is replaced only when the package's copy is newer, so
     *    restoring an old package cannot roll back an agent edited since;
     *  - the built-in is never touched, and nothing is deleted.
     * The result is normalized (count bound, dense sortOrder).
     */
    fun mergeBackup(
        local: List<SubAgentDefinition>,
        incoming: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): BackupMerge {
        val out = local.toMutableList()
        var written = 0
        var skipped = 0
        for (r in incoming) {
            if (r.isBuiltIn || SubAgentDefinition.isBuiltInId(r.id)) { skipped++; continue }
            val at = out.indexOfFirst { it.id == r.id }
            if (at >= 0) {
                if (r.updatedAt > out[at].updatedAt) { out[at] = r.copy(isBuiltIn = false); written++ } else skipped++
                continue
            }
            if (out.any { nameKey(it.name) == nameKey(r.name) }) {
                log?.invoke("backup sub agent '${r.name}' skipped: a local agent already has that name")
                skipped++
                continue
            }
            out.add(r.copy(isBuiltIn = false))
            written++
        }
        return BackupMerge(normalize(out, log), written, skipped)
    }
}
