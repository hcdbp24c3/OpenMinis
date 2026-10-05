import SwiftUI

/// [T-ios-repo-digest] Settings › Repository digest: how the tool authenticates, and
/// a way into the credential store.
///
/// This screen used to own four token fields, one per forge family. That was wrong for
/// two reasons — a company GitLab and gitlab.com want different tokens, and there was
/// nowhere to put an SSH key — so credentials now live in the git vault, keyed by HOST,
/// and this page only reports what is configured. Editing happens on the vault screen,
/// pointed at with the count so the user can tell at a glance whether the tool is
/// authenticated. Android twin: `ui/settings/RepoDigestSettingsScreen.kt`.
struct RepoDigestSettingsView: View {
    @State private var entries: [GitVaultEntry] = GitVault.entries()

    private var tokens: Int { entries.filter { $0.kind == .token && $0.enabled }.count }
    private var keys: Int { entries.filter { $0.kind == .sshKey && $0.enabled }.count }

    /// Counts are built with plain interpolation rather than AppLocalized: a missing
    /// catalog entry is the common case for a brand-new key, and a formatted count
    /// that renders as a raw "%lld" is worse than English.
    private var credentialSubtitle: String {
        if entries.isEmpty { return "No credentials saved" }
        if keys == 0 { return "\(tokens) token saved" }
        if tokens == 0 { return "\(keys) SSH key saved" }
        return "\(tokens) token and \(keys) SSH key saved"
    }

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    GitVaultView()
                } label: {
                    LabeledContent(AppLocalized("Git vault"), value: credentialSubtitle)
                }
            } header: {
                Text(AppLocalized("Credentials"))
            } footer: {
                Text(AppLocalized("The tool authenticates with the vault entry whose host matches the repository URL, so a company GitLab and gitlab.com can hold different tokens. Without one, public repositories still work: one API call per digest (60/hour unauthenticated), and file contents come from the raw host, outside that quota."))
            }
        }
        .navigationTitle(AppLocalized("Repository digest"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { entries = GitVault.entries() }
    }
}
