import SwiftUI

// [T-tools-granular-switches] Two independent switches over the optional tools.
//
//   Browser Use  (`browser_use`)                   — Settings › Tools, default ON.
//   Sub Agents   (`subagent_task` + `agent_status`) — Settings › Sub Agents, default ON.
//
// They are independent: browser_use is a mature capability and was wrongly
// bundled under the one-round "CyberAgent" master switch (cdbfd4f75).
// agent_status rides with subagent_task (same `if`, appear and vanish
// together) — a status/cancel tool is meaningless without delegation.
//
// [T-sub-agents-v1] The agents switch is OWNED BY the Sub Agents page, which is
// where its definitions live; this page shows Browser Use alone and does not
// cross-reference it. The storage key is unchanged, so the two bypass guards
// that read it directly (minis-scheduled child-of-current, and the dispatcher's
// refusal of a stale subagent_task call) follow automatically.
//
// Every other registered tool (shell_execute, file_read/write/edit,
// read_image, memory_write/get) stays always available and does not
// appear here — decided in review: they are the model's basic working
// set, not something to withhold per user.
//
// Off means removed, not refused: `makeAgentTools()` leaves the definition
// out of the schema and the system prompt drops the sentences that
// advertise it, so the model never learns the tool exists. The dispatcher
// and the `minis-browser-use` CLI bridge keep a belt-and-braces refusal for
// a request built before the switch flipped. A helper (child agent) obeys
// the same switches — delegation passed its own gate, but a switch can flip
// mid-run and the child's next turn must honour it (T-tools-master-switch).
enum AgentToolSwitch: String, CaseIterable {
    case browser = "agent.tools.browser.enabled"
    case agents = "agent.tools.agents.enabled"
    /// [T-ios-web-search] web_search. Key string is byte-identical to Android's
    /// `AgentToolSwitch.WEB_SEARCH` so the choice syncs with a user's settings.
    case webSearch = "agent.tools.websearch.enabled"
    /// [T-ios-web-fetch] web_fetch. Key string matches Android's
    /// `AgentToolSwitch.WEB_FETCH`.
    case webFetch = "agent.tools.webfetch.enabled"
    /// [T-ios-repo-digest] repo_digest. Same key string as Android.
    case repo = "agent.tools.repo.enabled"
    /// [T-ios-ask-user] ask_user_question. Same key string as Android.
    case ask = "agent.tools.ask.enabled"

    var key: String { rawValue }

    /// [T-sub-agents-ga] Both default ON.
    ///
    /// Sub Agents shipped behind this default-OFF switch while the feature was
    /// still settling; it has since stabilised and Android has defaulted it ON
    /// all along (AgentToolSwitch.kt, `AGENTS(..., true)`), so the two
    /// platforms disagreed on what a fresh install does. This closes that.
    ///
    /// The cost the old default guarded against is real and has not gone away —
    /// a fan-out of background agents spends real tokens — but it is a property
    /// of USING the feature, not of the feature being unfinished, and the
    /// footer on the Sub Agents page states it plainly. Withholding a stable
    /// capability from everyone who never opened Settings is the worse trade.
    ///
    /// A user who already set either switch keeps their value: UserDefaults
    /// then HAS an entry and this default is never consulted. So this flip
    /// reaches only people who never touched it — anyone who deliberately
    /// turned Sub Agents OFF stays off.
    var defaultValue: Bool {
        switch self {
        case .browser: return true
        case .agents: return true
        // [T-ios-web-search] ON, like Android: the default backend (DuckDuckGo)
        // needs no key, and the alternative for a fact is the browser tool.
        case .webSearch: return true
        // [T-ios-web-fetch] ON: it is the cheap path for reading one URL, and it
        // is SSRF-guarded.
        case .webFetch: return true
        // [T-ios-repo-digest] ON: the cheap alternative to cloning or browsing.
        case .repo: return true
        // [T-ios-ask-user] ON: it is what lets the agent ask instead of guess.
        case .ask: return true
        }
    }

    /// Nonisolated so the shell / CLI bridges can read it off the main actor.
    ///
    /// [T-sub-agents-v1] The [T-agents-debug-only] Release gate that forced
    /// `.agents` to false here is REMOVED: sub agents ship to everyone. This is
    /// the single read every consumer goes through — makeAgentTools(), the
    /// prompt builder, the dispatcher and the CLI bridges — so the switch and
    /// its default are the whole story.
    nonisolated var isEnabled: Bool {
        (UserDefaults.standard.object(forKey: key) as? Bool) ?? defaultValue
    }

    /// The switch governing a tool name; nil = always available.
    nonisolated static func governing(_ toolName: String) -> AgentToolSwitch? {
        switch toolName {
        case "browser_use": return .browser
        case SubAgentDefinition.toolName: return .agents
        case WebSearchTool.name: return .webSearch
        case WebFetchTool.name: return .webFetch
        case RepoDigestTool.name: return .repo
        // [T-ios-ask-user] The alias is the MCP's original name, so a transcript
        // or a model that learned the MCP spelling still resolves.
        case AskUserQuestion.name, AskUserQuestion.alias: return .ask
        default: return nil
        }
    }

    nonisolated static func isToolEnabled(_ toolName: String) -> Bool {
        governing(toolName)?.isEnabled ?? true
    }

    // MARK: Migration from the single master switch (cdbfd4f75)

    private static let migrationKey = "agent.tools.granular.v1.migrated"
    /// The one-round master switch that gated browser_use + delegate_task.
    static let legacyMasterKey = "agent.tools.enabled"

    /// Carry the user's earlier choice forward, once:
    /// - master switch explicitly ON  → agents ON (browser is on by default
    ///   now regardless);
    /// - master switch explicitly OFF, or the older Settings › Agents switch
    ///   explicitly OFF → agents OFF;
    /// - nothing set → nothing written; each switch takes its default.
    ///
    /// [T-sub-agents-ga] That middle case now MATTERS rather than merely
    /// pinning the default: with agents defaulting ON, writing the explicit
    /// `false` is the only thing that keeps a user who once switched
    /// delegation off from silently getting it back on upgrade. Leaving the
    /// write out — it used to be a no-op — would now reverse their decision.
    /// browser_use is a product decision (mature, default on), so a master
    /// OFF does not turn it off — that switch was about agents.
    static func migrateLegacyIfNeeded() {
        let d = UserDefaults.standard
        guard !d.bool(forKey: migrationKey) else { return }
        d.set(true, forKey: migrationKey)
        guard d.object(forKey: AgentToolSwitch.agents.key) == nil else { return }
        if let master = d.object(forKey: legacyMasterKey) as? Bool {
            d.set(master, forKey: AgentToolSwitch.agents.key)
        } else if let helpers = d.object(forKey: HelperSettingsView.legacyEnabledKey) as? Bool, !helpers {
            d.set(false, forKey: AgentToolSwitch.agents.key)
        }
    }
}

struct ToolsSettingsView: View {
    @AppStorage(AgentToolSwitch.browser.key) private var browserEnabled: Bool = AgentToolSwitch.browser.defaultValue
    @AppStorage(AgentToolSwitch.webSearch.key) private var webSearchEnabled: Bool = AgentToolSwitch.webSearch.defaultValue
    @AppStorage(AgentToolSwitch.webFetch.key) private var webFetchEnabled: Bool = AgentToolSwitch.webFetch.defaultValue
    @AppStorage(AgentToolSwitch.repo.key) private var repoEnabled: Bool = AgentToolSwitch.repo.defaultValue
    @AppStorage(AgentToolSwitch.ask.key) private var askEnabled: Bool = AgentToolSwitch.ask.defaultValue

    var body: some View {
        Form {
            Section {
                toolRow(AppLocalized("Browser Use"), tool: "browser_use", icon: "globe", isOn: $browserEnabled)
            } footer: {
                Text(AppLocalized("Browse the web in the in-app browser: open pages, read them, click and type. Stable and on by default. When off, the tool is removed from every conversation — the model does not see it at all."))
            }
            // [T-ios-web-search] Same kind of choice as Browser Use — may the
            // agent reach the network on its own — with a different cost: one
            // request instead of a WebView page load.
            Section {
                toolRow(AppLocalized("Web Search"), tool: WebSearchTool.name, icon: "magnifyingglass", isOn: $webSearchEnabled)
                NavigationLink {
                    WebSearchSettingsView()
                } label: {
                    Label(AppLocalized("Search engine"), systemImage: "gearshape")
                }
            } footer: {
                Text(AppLocalized("Search the web and get titles, URLs and snippets in one request — usually far faster than opening a page with the browser tool. Pick the engine and add API keys on the Search engine screen. Off removes the tool from every conversation."))
            }
            // [T-ios-web-fetch] The one-URL reader: headers, POST bodies, and an
            // optional real-browser render for pages that need JavaScript.
            Section {
                toolRow(AppLocalized("Web Fetch"), tool: WebFetchTool.name, icon: "arrow.down.doc", isOn: $webFetchEnabled)
            } footer: {
                Text(AppLocalized("Fetch one URL and read it as text, markdown, raw HTML or pretty JSON — with custom headers, POST bodies, and an optional JavaScript render. Cheaper than driving the browser and safer than curling from the terminal (private and metadata hosts are blocked)."))
            }
            // [T-ios-repo-digest] One call reads a whole repository, instead of a
            // clone or a page-by-page browse.
            Section {
                toolRow(AppLocalized("Repository digest"), tool: RepoDigestTool.name, icon: "chevron.left.forwardslash.chevron.right", isOn: $repoEnabled)
                // [T-git-vault] Credentials live in the vault, keyed by host, and are
                // shared with `git` in the terminal; this page only explains how the
                // tool authenticates and links there.
                NavigationLink {
                    RepoDigestSettingsView()
                } label: {
                    Label(AppLocalized("Credentials"), systemImage: "key")
                }
                NavigationLink {
                    GitVaultView()
                } label: {
                    Label(AppLocalized("Git vault"), systemImage: "key.horizontal")
                }
            } footer: {
                Text(AppLocalized("Read a GitHub repository as a digest — file tree plus the contents that matter — in one call. Binary, vendored, minified and lock files are skipped; include/exclude globs narrow it further."))
            }
            // [T-ios-ask-user] The one tool that hands control back to the user.
            Section {
                toolRow(AppLocalized("Ask user"), tool: AskUserQuestion.name, icon: "questionmark.circle", isOn: $askEnabled)
            } footer: {
                Text(AppLocalized("Let the agent ask you a structured multiple-choice question when a choice is genuinely ambiguous, instead of guessing. The run pauses until you answer or skip."))
            }
            // [T-sub-agents-v1] No Agents section here, and deliberately not
            // even a read-only pointer row: the switch belongs to the Sub Agents
            // page, next to the definitions it governs.
        }
        .navigationTitle(AppLocalized("Agent Tools"))
        .navigationBarTitleDisplayMode(.inline)
    }

    func toolRow(_ title: String, tool: String, icon: String, iconSize: CGFloat = 14,
                 accent: Color = Color.accentColor, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: iconSize))
                    .foregroundStyle(isOn.wrappedValue ? accent : Color.secondary)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    Text(tool).font(.caption.monospaced()).foregroundStyle(.secondary)
                }
            }
        }
    }
}

extension AIChatViewModel {
    /// Per-tool gate (Settings › Tools).
    nonisolated static func toolEnabled(_ sw: AgentToolSwitch) -> Bool { sw.isEnabled }

    /// The refusal a stale call gets if a request built before the switch
    /// flipped still names a gated tool.
    static let toolsDisabledMessage =
        "This tool is turned off in the app's settings. Tell the user they can enable it there; do not retry."
}
