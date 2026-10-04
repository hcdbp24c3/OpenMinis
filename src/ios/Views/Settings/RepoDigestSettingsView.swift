import SwiftUI

/// [T-ios-repo-digest] Settings › Repository digest: the GitHub token
/// `RepoDigestTool` uses.
///
/// One field, because that is the whole configuration. Without a token the tool
/// still reads public repositories (60 API calls per hour, and only ONE of them is
/// needed per digest — file contents come from the raw host, which is not part of
/// the API quota); with one it reaches private repositories and 5000 calls/hour.
struct RepoDigestSettingsView: View {
    @State private var token: String = RepoDigestPrefs.token

    var body: some View {
        Form {
            Section {
                SecureField(AppLocalized("ghp_… or github_pat_…"), text: $token)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.footnote, design: .monospaced))
                    .onChange(of: token) { newValue in
                        RepoDigestPrefs.token = newValue
                    }
            } header: {
                Text(AppLocalized("GitHub token (optional)"))
            } footer: {
                Text(AppLocalized("Without a token, public repositories still work: one GitHub API call per digest (60/hour, unauthenticated) plus file contents from the raw host, which is not part of that quota. A token raises the limit to 5000/hour and lets the tool read private repositories."))
            }
        }
        .navigationTitle(AppLocalized("Repository digest"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
