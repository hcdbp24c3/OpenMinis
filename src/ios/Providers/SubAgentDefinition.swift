import Foundation

/// Hard limits on the Sub Agent roster.
///
/// [T-sub-agents-v1] These are not defensive "just in case" numbers: the roster
/// is injected into the main conversation's system prompt on every turn, so the
/// count and the description length ARE the fixed per-request cost. Bounding the
/// input is what removes the need for a runtime token warning — a
/// user cannot configure a roster that blows the budget. At the maximum
/// (10 × (40 + 200) chars) the roster is ~800 tokens.
///
/// Shared by the editor UI, the store's load-time clamp and the roster
/// generator, so the three cannot drift apart.
enum SubAgentLimits {
    /// [T-sub-agent-builtin-roster] Custom (user-created) agents a roster may hold.
    ///
    /// This is the number that must NOT move when built-ins are added. It replaces a
    /// single `maxCount = 10` written when exactly one built-in shipped, and which the
    /// extra built-ins then silently ate into: the bound was applied to
    /// `maxCount - builtIns.count`, so a user who had nine custom agents before an
    /// upgrade would have lost six of them to a roster rule they never touched. A roster
    /// that was legal before an upgrade stays legal after it.
    static let maxCustom = 9

    /// Ceiling on the whole roster the model reads (built-ins + `maxCustom`).
    ///
    /// Built-ins DO count towards the prompt: the roster is injected every turn, so the
    /// absolute size is what is being bounded. This ceiling exists for a future build
    /// that ships many more built-ins — it caps that growth without ever touching the
    /// user's own allowance.
    static let maxTotal = 16

    static let nameMaxLength = 40
    /// The only free text that reaches the main conversation.
    static let descriptionMaxLength = 200
    /// Child-session only; bounded so one definition cannot eat the child's context.
    static let instructionsMaxLength = 4000
}

/// One named sub agent the main model can delegate to by name.
///
/// [T-sub-agents-v1] Sits on top of the existing primary/sub tier mechanism
/// rather than replacing it: a definition with no `modelGroupId` keeps today's
/// behaviour exactly (the delegating model picks a tier per call).
struct SubAgentDefinition: Identifiable, Codable, Hashable {
    /// The built-in definition's fixed id. Its display name is localizable and
    /// user-editable; the id is what code and persisted payloads key on.
    static let builtInId = "builtin.general"

    /// [T-sub-agents-v1] The sub agent tool's wire name.
    ///
    /// Renamed from `delegate_task` so it reads as "the sub agent tool" rather
    /// than a generic verb, matching the Sub Agents wording everywhere else.
    /// The separate `agent_status` tool is folded in as an `action`, so the
    /// model sees ONE tool for delegating and for
    /// inspecting or stopping what it delegated.
    ///
    /// No compatibility shim for the old names: the feature has not shipped, so
    /// there are no transcripts in the wild carrying them.
    static let toolName = "subagent_task"

    /// [T-sub-agents-v1] The built-in's stored name, deliberately NOT localized.
    ///
    /// `name` is an identifier before it is a label: it is the `enum` of
    /// `delegate_task.agent`, the value the model has to emit, and the key
    /// `SubAgentRoster.resolve(name:)` matches on. Localizing it would put the
    /// UI language into the tool schema, make the model emit non-ASCII
    /// identifiers, and — worse — break every stored reference the moment the
    /// user switched language or synced to a device set to another one.
    ///
    /// The UI shows `displayName` instead, which localizes this one value for
    /// presentation only. User-created agents are unaffected: their names are
    /// whatever the user typed, in whatever language they typed it.
    static let builtInName = "General Sub Agent"

    /// Also not localized: this goes into the system-prompt roster the model
    /// reads to decide what to delegate. It should say the same thing whatever
    /// language the app's UI happens to be in — the model is not the user.
    /// `displayDescription` localizes it for the settings screen.
    static let builtInDescription =
        "Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, "
        + "digesting bulk output into a conclusion, or running independent branches in parallel."

    /// Localized label for the settings list and editor. Only the built-ins have one —
    /// a user-created agent is shown exactly as the user named it, and a built-in this
    /// build does not know (a roster synced from a newer one) keeps its stored text.
    ///
    /// Keyed by id with LITERAL keys: `AppLocalized` takes a `LocalizationValue`, which a
    /// runtime `String` cannot become, and a literal switch keeps the labels in the
    /// catalog where the translators can see them.
    var displayName: String {
        guard isBuiltIn else { return name }
        switch id {
        case Self.builtInId: return AppLocalized("General Sub Agent")
        case Self.scoutId: return AppLocalized("Recon Sub Agent")
        case Self.reviewerId: return AppLocalized("Code Review Sub Agent")
        case Self.testerId: return AppLocalized("Test Runner Sub Agent")
        case Self.researcherId: return AppLocalized("Research Sub Agent")
        case Self.debuggerId: return AppLocalized("Debugger Sub Agent")
        case Self.architectId: return AppLocalized("Architect Sub Agent")
        default: return name
        }
    }

    /// Localized description for the settings screen, mirroring `displayName`.
    var displayDescription: String {
        guard isBuiltIn else { return description }
        switch id {
        case Self.builtInId:
            return AppLocalized("Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, digesting bulk output into a conclusion, or running independent branches in parallel.")
        case Self.scoutId:
            return AppLocalized("Read-only reconnaissance: map a codebase, a repository or a set of pages and return a compact brief — where things live, how they connect, and what to read next. Told not to edit.")
        case Self.reviewerId:
            return AppLocalized("Review a change or a file for correctness, security and test coverage, and report concrete findings with the file and line. Told not to fix: the finding is the deliverable.")
        case Self.testerId:
            return AppLocalized("Verify a change by running the project's build and tests in the sandbox, then report the exact command, its output and its exit status — green or red, with the evidence.")
        case Self.researcherId:
            return AppLocalized("Answer a question from OUTSIDE sources: run several search queries, read the pages that matter, cross-check what they claim, and report it with the URLs. Says when sources disagree.")
        case Self.debuggerId:
            return AppLocalized("Find the CAUSE of a failure: reproduce it, shrink it to the smallest case, test one hypothesis at a time, and report the root cause with the evidence. Told not to fix.")
        case Self.architectId:
            return AppLocalized("Decide how a change should be built before any code exists: read what is already there, weigh two or three options, and return a plan with its tradeoffs and unknowns.")
        default:
            return description
        }
    }

    let id: String
    var name: String
    /// What the main model reads to decide whether to pick this agent.
    /// Bounded because it is the only part that costs main-conversation tokens.
    var description: String
    /// Appended to the child session's brief. Empty = nothing appended.
    var instructions: String
    /// nil = the delegating model chooses primary/sub per task (today's behaviour).
    var modelGroupId: String?
    /// [T-subagent-thinking-override] Reasoning intensity for runs of this sub
    /// agent, overriding whatever the resolved model group defaults to.
    ///
    /// nil = inherit (the group's `defaultThinkingLevel`, else the parent
    /// conversation) — the existing behaviour, and what every definition has
    /// until the user sets one. Mirrors `ModelGroup.defaultThinkingLevel`: a
    /// group sets the default for sessions bound to it, and this overrides that
    /// for this sub agent, the same way a session-level pick overrides a group.
    var thinkingLevelOverride: ThinkingLevel?
    let isBuiltIn: Bool
    var sortOrder: Int
    var updatedAt: Date

    init(id: String = UUID().uuidString,
         name: String,
         description: String,
         instructions: String = "",
         modelGroupId: String? = nil,
         thinkingLevelOverride: ThinkingLevel? = nil,
         isBuiltIn: Bool = false,
         sortOrder: Int = 0,
         updatedAt: Date = Date()) {
        self.id = id
        self.name = name
        self.description = description
        self.instructions = instructions
        self.modelGroupId = modelGroupId
        self.thinkingLevelOverride = thinkingLevelOverride
        self.isBuiltIn = isBuiltIn
        self.sortOrder = sortOrder
        self.updatedAt = updatedAt
    }

    /// The built-in general sub agent, inserted by `ensureBuiltIn` when absent.
    ///
    // MARK: - Built-in roster

    /// [T-sub-agent-builtin-roster] Three more built-ins beside the general one, one
    /// per SHAPE of delegated work: find, judge, verify. Mirrors Android
    /// `SubAgentDefinition.makeBuiltIns()` — same ids, same canonical English
    /// name/description, because those two strings are the tool-schema enum and the
    /// roster the model reads on both platforms.
    ///
    /// They ship because a sub agent's value is largely in what it is told to do, and
    /// these three shapes are the ones the delegation prompt keeps describing by hand
    /// ("search the repo and report, do not edit"; "review this change"; "run the tests
    /// and tell me what happened"). A user can still write their own — and often
    /// should, for a domain-specific reviewer — but the common three no longer have to
    /// be typed out from scratch.
    ///
    /// Cost: the roster is injected into every turn's system prompt, so each entry is
    /// name + description (bounded by `SubAgentLimits`) and nothing else — instructions
    /// go to the child session only, and stay empty here so the user's own wording is
    /// not second-guessed by a default they did not write.
    static let scoutId = "builtin.scout"
    static let scoutName = "Recon Sub Agent"
    static let scoutDescription =
        "Read-only reconnaissance: map a codebase, a repository or a set of pages and return a compact "
        + "brief — where things live, how they connect, and what to read next. Told not to edit."

    static let reviewerId = "builtin.reviewer"
    static let reviewerName = "Code Review Sub Agent"
    static let reviewerDescription =
        "Review a change or a file for correctness, security and test coverage, and report concrete "
        + "findings with the file and line. Told not to fix: the finding is the deliverable."

    static let testerId = "builtin.tester"
    static let testerName = "Test Runner Sub Agent"
    static let testerDescription =
        "Verify a change by running the project's build and tests in the sandbox, then report the exact "
        + "command, its output and its exit status — green or red, with the evidence."

    /// Every built-in id, in roster order: the order is the disclosure order, and the
    /// first entry is what a blank agent name resolves to.
    /// [T-sub-agent-builtin-roster] Three more built-ins, taken from what the published
    /// sub-agent catalogs converge on (VoltAgent's awesome-claude-code-subagents,
    /// jurabek/sub-agents): the roles those collections list most, and which are genuinely
    /// distinct SHAPES of work rather than domains.
    ///
    ///  - research (their `search-specialist`): answer from OUTSIDE sources, with URLs and
    ///    cross-checking. `scoutId` maps a codebase; this one goes out to the web and is
    ///    accountable for its sources.
    ///  - debug (their `debugger`): find the cause of a failure that already happened.
    ///    `testerId` verifies a change that exists; this one establishes a fact that does
    ///    not — what is broken, and why.
    ///  - architect (their `architect-review`): decide the shape of a change before code
    ///    is written. `reviewerId` judges code that exists; this judges a decision that
    ///    does not.
    ///
    /// Their prompts are 5-10k characters of capability lists. These are deliberately not:
    /// the value is the PROCESS and the output CONTRACT, and a child session handed a wall
    /// of buzzwords spends its context on the wall. Each brief is one screen, names the
    /// tools this app actually has, and states what the child must hand back.
    static let researcherId = "builtin.researcher"
    static let researcherName = "Research Sub Agent"
    static let researcherDescription =
        "Answer a question from OUTSIDE sources: run several search queries, read the pages that matter, "
        + "cross-check what they claim, and report it with the URLs. Says when sources disagree."

    static let debuggerId = "builtin.debugger"
    static let debuggerName = "Debugger Sub Agent"
    static let debuggerDescription =
        "Find the CAUSE of a failure: reproduce it, shrink it to the smallest case, test one hypothesis "
        + "at a time, and report the root cause with the evidence. Told not to fix."

    static let architectId = "builtin.architect"
    static let architectName = "Architect Sub Agent"
    static let architectDescription =
        "Decide how a change should be built before any code exists: read what is already there, weigh "
        + "two or three options, and return a plan with its tradeoffs and unknowns."

    static let researcherInstructions = """
    You research a question from sources OUTSIDE this machine. The parent keeps your conclusion and discards your reading, so the conclusion has to stand on its own.

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
    4. The queries you used, listed compactly.
    """

    static let debuggerInstructions = """
    You find the cause of something that is broken. The parent gets your conclusion, not your session, so a precise cause beats a long transcript.

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
    6. If you could not isolate it: what you ruled OUT, and the next experiment you would run.
    """

    static let architectInstructions = """
    You decide HOW something should be built or changed, before any code is written. The parent implements from your plan, so it has to be specific enough to follow and honest about what you do not know.

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
    5. What would make you change the recommendation.
    """

    static let allBuiltInIds = [builtInId, scoutId, reviewerId, testerId, researcherId, debuggerId, architectId]

    /// Whether `id` belongs to a built-in this build knows.
    static func isBuiltInId(_ id: String) -> Bool { allBuiltInIds.contains(id) }

    /// The canonical spec for every built-in, in roster order.
    /// `SubAgentRoster.normalize` starts from this and folds each stored row's
    /// user-owned fields back in, which is what makes a roster written by an older
    /// build (only `builtin.general`) gain the new ones instead of losing them forever.
    static func makeBuiltIns() -> [SubAgentDefinition] {
        [
            makeBuiltIn(sortOrder: 0),
            SubAgentDefinition(id: scoutId, name: scoutName, description: scoutDescription,
                               isBuiltIn: true, sortOrder: 1),
            SubAgentDefinition(id: reviewerId, name: reviewerName, description: reviewerDescription,
                               isBuiltIn: true, sortOrder: 2),
            SubAgentDefinition(id: testerId, name: testerName, description: testerDescription,
                               isBuiltIn: true, sortOrder: 3),
            SubAgentDefinition(id: researcherId, name: researcherName, description: researcherDescription,
                               instructions: researcherInstructions, isBuiltIn: true, sortOrder: 4),
            SubAgentDefinition(id: debuggerId, name: debuggerName, description: debuggerDescription,
                               instructions: debuggerInstructions, isBuiltIn: true, sortOrder: 5),
            SubAgentDefinition(id: architectId, name: architectName, description: architectDescription,
                               instructions: architectInstructions, isBuiltIn: true, sortOrder: 6),
        ]
    }

    /// Its name and description are localized at creation time. They are stored
    /// (not resolved per read) because the name is a user-editable field and the
    /// description is what the model sees — a value that changed under the user
    /// when they switched language would be worse than a stale one.
    static func makeBuiltIn(sortOrder: Int = 0) -> SubAgentDefinition {
        SubAgentDefinition(
            id: builtInId,
            name: builtInName,
            // Read by the delegating model to decide what to hand over, so it
            // names the shapes of work that pay off — many rounds of searching,
            // bulk output it only needs a conclusion from, independent branches
            // it can run at once — rather than merely saying "general". Kept
            // under the 200-char bound the roster budget assumes.
            description: builtInDescription,
            instructions: "",
            modelGroupId: nil,
            isBuiltIn: true,
            sortOrder: sortOrder
        )
    }

    // MARK: - Clamping

    /// Field-level clamp applied on load and on save.
    ///
    /// Synced data can come from a future build or a hand-edited file, so the UI
    /// limits alone are not enough — see `SubAgentRoster.normalize`.
    func clamped() -> SubAgentDefinition {
        var copy = self
        copy.name = String(name.prefix(SubAgentLimits.nameMaxLength))
        copy.description = String(description.prefix(SubAgentLimits.descriptionMaxLength))
        copy.instructions = String(instructions.prefix(SubAgentLimits.instructionsMaxLength))
        return copy
    }

    /// Whether any field was over its limit — used only to decide whether to log.
    var exceedsLimits: Bool {
        name.count > SubAgentLimits.nameMaxLength
            || description.count > SubAgentLimits.descriptionMaxLength
            || instructions.count > SubAgentLimits.instructionsMaxLength
    }
}

/// Roster-level rules: the built-in must exist, the list is bounded, ordering is
/// the disclosure order.
///
/// Free functions on the array rather than a store type, so the load path, the
/// sync merge and the tests all share one implementation.
enum SubAgentRoster {

    /// Normalise a decoded roster: guarantee the built-in, clamp every field,
    /// bound the count, and renumber `sortOrder` densely from 0.
    ///
    /// [T-sub-agents-v1] NEVER throws and never drops the built-in: this runs on
    /// data that arrived over iCloud from a possibly newer build, and a bad
    /// roster must not be able to block startup. Anything discarded is logged.
    static func normalize(_ input: [SubAgentDefinition],
                          log: ((String) -> Void)? = nil) -> [SubAgentDefinition] {
        var list = input

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
        let storedById = Dictionary(grouping: list, by: \.id)
        let builtIns: [SubAgentDefinition] = SubAgentDefinition.makeBuiltIns().map { spec in
            guard let row = storedById[spec.id]?.first else {
                log?("[SubAgents] built-in '\(spec.id)' missing — reinserting")
                return spec
            }
            if row.name != spec.name || row.description != spec.description {
                log?("[SubAgents] restoring the built-in's canonical name/description")
            }
            var folded = spec
            folded.instructions = row.instructions
            folded.modelGroupId = row.modelGroupId
            folded.thinkingLevelOverride = row.thinkingLevelOverride
            folded.updatedAt = row.updatedAt
            return folded
        }

        // Custom entries keep the user's order; ties break by id so the result
        // is deterministic across devices.
        var custom = list.filter { !SubAgentDefinition.isBuiltInId($0.id) }
        custom.sort { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }

        // Drop anything past the bound (the built-ins already hold their slots).
        // The CUSTOM allowance, which the built-ins do not eat into (see
        // SubAgentLimits.maxCustom); only the total roster is capped.
        let allowedCustom = min(SubAgentLimits.maxCustom,
                                max(0, SubAgentLimits.maxTotal - builtIns.count))
        if custom.count > allowedCustom {
            let dropped = custom.suffix(from: allowedCustom).map(\.name)
            log?("[SubAgents] roster over the limit — keeping \(allowedCustom) of \(custom.count) custom definitions, dropping: \(dropped.joined(separator: ", "))")
            custom = Array(custom.prefix(allowedCustom))
        }

        if builtIns.contains(where: { $0.exceedsLimits }) || custom.contains(where: { $0.exceedsLimits }) {
            log?("[SubAgents] one or more definitions exceeded field limits — truncating")
        }

        // A custom definition must not claim a built-in identity: `isBuiltIn` drives
        // "cannot delete" in the UI and the ids are filtered above, so anything left
        // asserting the flag is an impostor. A nameless row cannot be addressed (the
        // name IS the enum the model emits), and a name that duplicates one already
        // in the roster is unreachable because resolution is first-match.
        var out: [SubAgentDefinition] = builtIns.enumerated().map { index, def in
            var d = def.clamped()
            d.sortOrder = index
            return d
        }
        var seenNames = Set(out.map { nameKey($0.name) })
        var next = out.count
        for def in custom {
            if def.isBuiltIn { continue }
            if def.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            if seenNames.contains(nameKey(def.name)) { continue }
            seenNames.insert(nameKey(def.name))
            var d = def.clamped()
            d.sortOrder = next
            out.append(d)
            next += 1
        }
        return out
    }

    /// The definition a delegation should run under.
    ///
    /// Matching is case-insensitive and whitespace-trimmed because the name
    /// comes back from a model. `nil` name = the built-in. A name that matches
    /// nothing returns nil, which the caller turns into `unknown_agent`.
    static func resolve(name: String?, in roster: [SubAgentDefinition]) -> SubAgentDefinition? {
        guard let raw = name?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return roster.first { $0.id == SubAgentDefinition.builtInId } ?? roster.first
        }
        return roster.first { nameKey($0.name) == nameKey(raw) }
    }

    /// [T-subagent-sync-dedupe] The canonical form two names are compared by.
    ///
    /// Folded exactly the way `resolve(name:)` matches — trimmed, case- and
    /// diacritic-insensitive — so "two agents the merge considers the same" and
    /// "two agents the model cannot tell apart" are by construction the same
    /// question. If these ever diverge, the merge would keep a pair that
    /// `resolve` can only ever reach one of, which is the bug this exists to
    /// prevent.
    static func nameKey(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    /// [T-subagent-sync-dedupe] Merge two rosters, collapsing same-NAME records
    /// that carry different ids.
    ///
    /// Why name and not id: ids are UUIDs minted per device, but the NAME is
    /// what the model emits and what `resolve(name:)` matches on. Two devices
    /// that each add "coding-agent" produce two records that are distinct by id
    /// and indistinguishable to the model — the roster is injected every turn,
    /// so the duplicate costs prompt budget on all of them, `resolve` can only
    /// ever reach the first, and both count against `SubAgentLimits.maxCustom`.
    ///
    /// Whole-record replacement, NOT a field-level merge: the loser is dropped
    /// entirely rather than having its instructions/modelGroupId folded into the
    /// winner. That matches the whole-blob granularity the rest of
    /// ProviderConfigV2 syncs at, and a field-level merge of two independently
    /// authored agents would synthesise a third agent neither user wrote.
    ///
    /// Determinism is the point of the tie-break. Both devices run this against
    /// mirrored inputs and must reach the SAME winner without talking to each
    /// other, or they re-upload rival rosters forever. Newer `updatedAt` wins;
    /// on a tie the lexicographically smaller id wins — the same rule, and the
    /// same reasoning, as the model-group name collapse in
    /// `CloudSyncEngine.mergeProviderConfig`.
    ///
    /// The built-in never loses and is never deduped away: `normalize` pins it
    /// first and restores its canonical name, so a remote record that collides
    /// with its name is the record that gets dropped.
    static func merge(local: [SubAgentDefinition],
                      remote: [SubAgentDefinition],
                      log: ((String) -> Void)? = nil) -> [SubAgentDefinition] {
        var winners: [String: SubAgentDefinition] = [:]   // nameKey -> winner
        var order: [String] = []                          // nameKey, first-seen

        func consider(_ def: SubAgentDefinition) {
            let key = nameKey(def.name)
            guard let held = winners[key] else {
                winners[key] = def
                order.append(key)
                return
            }
            if held.id == def.id {
                // Same record on both sides — ordinary last-writer-wins.
                if def.updatedAt > held.updatedAt { winners[key] = def }
                return
            }
            // The built-in outranks any same-named custom record, whichever
            // side it came from and whatever its timestamp says.
            if SubAgentDefinition.isBuiltInId(held.id) { return }
            if SubAgentDefinition.isBuiltInId(def.id) {
                winners[key] = def
                return
            }
            let winner: SubAgentDefinition
            if def.updatedAt != held.updatedAt {
                winner = def.updatedAt > held.updatedAt ? def : held
            } else {
                winner = def.id < held.id ? def : held
            }
            let loser = winner.id == def.id ? held : def
            winners[key] = winner
            log?("[SubAgents] name collision '\(def.name)' — kept \(winner.id.prefix(8)), dropped \(loser.id.prefix(8))")
        }

        // Local first so a first-seen ordering favours the arrangement the user
        // already sees on this device; `normalize` renumbers sortOrder after.
        for d in local { consider(d) }
        for d in remote { consider(d) }

        return normalize(order.compactMap { winners[$0] }, log: log)
    }
}
