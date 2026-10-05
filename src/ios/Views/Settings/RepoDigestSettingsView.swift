import SwiftUI

/// [T-ios-repo-digest] Settings › Repository digest: the GitHub token
/// `RepoDigestTool` uses.
///
/// One field, because that is the whole configuration. Without a token the tool
/// still reads public repositories (60 API calls per hour, and only ONE of them is
/// needed per digest — file contents come from the raw host, which is not part of
/// the API quota); with one it reaches private repositories and 5000 calls/hour.
struct RepoDigestSettingsView: View {
    /// One field per host family, because the auth schemes genuinely differ —
    /// GitHub and Bitbucket take a Bearer token, GitLab wants `PRIVATE-TOKEN`,
    /// Gitea/Forgejo expect `token …` — so a single field would either be sent with
    /// a header some hosts reject or invite pasting a credential into the wrong slot.
    @State private var github: String = RepoDigestPrefs.token(for: .github)
    @State private var gitlab: String = RepoDigestPrefs.token(for: .gitlab)
    @State private var gitea: String = RepoDigestPrefs.token(for: .gitea)
    @State private var bitbucket: String = RepoDigestPrefs.token(for: .bitbucket)

    var body: some View {
        Form {
            tokenField(AppLocalized("GitHub token (optional)"), text: $github) { RepoDigestPrefs.setToken($0, for: .github) }
            tokenField(AppLocalized("GitLab token (optional)"), text: $gitlab) { RepoDigestPrefs.setToken($0, for: .gitlab) }
            tokenField(AppLocalized("Gitea / Forgejo / Codeberg token (optional)"), text: $gitea) { RepoDigestPrefs.setToken($0, for: .gitea) }
            tokenField(AppLocalized("Bitbucket app password or access token (optional)"), text: $bitbucket) { RepoDigestPrefs.setToken($0, for: .bitbucket) }
        }
        .navigationTitle(AppLocalized("Repository digest"))
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            Text(AppLocalized("Tokens are optional: public repositories on every supported host work without one. A GitHub or GitLab token raises the API limit (60 → 5000 requests/hour) and reaches private repositories. The tool reports which host it recognised."))
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(12)
        }
    }

    @ViewBuilder
    private func tokenField(_ label: String, text: Binding<String>, onCommit: @escaping (String) -> Void) -> some View {
        Section {
            SecureField(AppLocalized("ghp_… or github_pat_…"), text: text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(.footnote, design: .monospaced))
                .onChange(of: text.wrappedValue) { newValue in onCommit(newValue) }
        } header: {
            Text(label)
        }
    }
}
