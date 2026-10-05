import SwiftUI

/// [T-git-vault] Settings › Git vault: every git credential the app holds, matched to
/// a host, and the projection of them into the iSH rootfs. Android twin:
/// `ui/settings/GitVaultScreen.kt`.
///
/// Secret handling: an existing secret is never displayed. The field shows
/// ``GitVault/masked(_:)`` as its placeholder and only replaces the stored value when
/// the user actually types, so opening an entry to fix its host cannot blank a working
/// token.
struct GitVaultView: View {
    @State private var entries: [GitVaultEntry] = GitVault.entries()
    @State private var editing: GitVaultEditorTarget?
    @State private var syncResult: GitVaultSyncResult?
    @State private var syncNote: String?

    /// Wrapper so `.sheet(item:)` can distinguish "edit entry X" from "add".
    struct GitVaultEditorTarget: Identifiable {
        let id: String
        let entry: GitVaultEntry?
    }

    var body: some View {
        List {
            Section {
                if entries.isEmpty {
                    Button {
                        editing = GitVaultEditorTarget(id: "", entry: nil)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(AppLocalized("No credentials yet"))
                            Text(AppLocalized("Add a token or an SSH key for private repositories. Public ones work without one."))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                } else {
                    ForEach(entries) { entry in
                        Button {
                            editing = GitVaultEditorTarget(id: entry.id, entry: entry)
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(entry.host).font(.body)
                                Text(subtitle(for: entry))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    Button {
                        editing = GitVaultEditorTarget(id: "", entry: nil)
                    } label: {
                        Label(AppLocalized("Add credential"), systemImage: "plus")
                    }
                }
            } header: {
                Text(AppLocalized("Credentials"))
            } footer: {
                Text(AppLocalized("Each entry is matched to the host a remote names: an exact name (gitlab.com), a wildcard for its subdomains (*.corp.example), or * for any host. The API tools and `git` in the terminal both read this store."))
            }

            Section {
                Button {
                    syncNow()
                } label: {
                    Label(AppLocalized("Sync to sandbox"), systemImage: "arrow.triangle.2.circlepath")
                }
            } header: {
                Text(AppLocalized("Sandbox"))
            } footer: {
                Text(syncFooter)
            }
        }
        .navigationTitle(AppLocalized("Git vault"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { target in
            NavigationStack {
                GitVaultEditorView(entry: target.entry) { updated, secret, passphrase in
                    if let secret { GitVault.setSecret(id: updated.id, value: secret) }
                    if let passphrase { GitVault.setPassphrase(id: updated.id, value: passphrase) }
                    GitVault.save(updated)
                    entries = GitVault.entries()
                    editing = nil
                    syncNow()
                } onDelete: {
                    guard let id = target.entry?.id else { return }
                    GitVault.delete(id: id)
                    entries = GitVault.entries()
                    editing = nil
                    syncNow()
                }
            }
        }
        .onAppear(perform: onAppear)
    }

    private func onAppear() {
        // One-shot migration of the four token fields this app used to keep for
        // repo_digest, then a sync so the sandbox reflects the vault on entry.
        GitVault.importRepoDigestLegacyOnce()
        entries = GitVault.entries()
        syncNow()
    }

    /// Push the vault into the rootfs, so the terminal picks up a new key without a
    /// second, forgettable step. Skipped before the rootfs exists: writing into
    /// `alpine-rootfs/data/root` first would race the installer, which replaces that
    /// whole tree when it starts.
    private func syncNow() {
        syncNote = nil
        guard RootfsManager.shared.isInstalled else {
            syncResult = nil
            syncNote = AppLocalized("Sandbox is not installed yet")
            return
        }
        syncResult = GitVaultMaterializer.sync(dataPath: RootfsManager.shared.dataPath)
    }

    private var syncFooter: String {
        if let syncNote { return syncNote }
        guard let result = syncResult else {
            return AppLocalized("Write ~/.git-credentials and ~/.ssh/config into the sandbox")
        }
        var lines = ["Wrote \(result.tokens) token and \(result.keys) key to \(result.home)"]
        if result.skipped > 0 { lines.append("\(result.skipped) entry has no secret yet") }
        if result.wildcards > 0 {
            lines.append("\(result.wildcards) wildcard token applies to the API tools only — git needs an exact host")
        }
        return lines.joined(separator: "\n")
    }

    private func subtitle(for entry: GitVaultEntry) -> String {
        let kind = entry.kind == .sshKey
            ? "SSH key"
            : "HTTPS token"
        let stored = GitVault.hasSecret(id: entry.id)
            ? GitVault.masked(GitVault.secret(id: entry.id))
            : "no secret yet"
        let note = entry.note.isEmpty ? "" : " · \(entry.note)"
        let state = entry.enabled ? "" : " · disabled"
        return "\(kind) · \(stored)\(note)\(state)"
    }
}

/// The add/edit sheet. `entry` nil means "new".
///
/// Once a secret is stored the field starts empty with the masked value as its
/// placeholder, and the save closure receives nil for "unchanged" — typing replaces it,
/// and clearing it is only possible for a new entry (there is a Delete for the other
/// case).
struct GitVaultEditorView: View {
    let entry: GitVaultEntry?
    let onSave: (GitVaultEntry, String?, String?) -> Void
    let onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var host: String
    @State private var kind: GitVaultKind
    @State private var username: String
    @State private var note: String
    @State private var enabled: Bool
    @State private var secret: String = ""
    @State private var passphrase: String = ""
    @State private var reveal = false

    private let id: String
    private let storedSecret: String
    private var isNew: Bool { entry == nil }
    private var isSsh: Bool { kind == .sshKey }
    private var normalizedHost: String { GitVault.normalizePattern(host) }

    init(
        entry: GitVaultEntry?,
        onSave: @escaping (GitVaultEntry, String?, String?) -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.entry = entry
        self.onSave = onSave
        self.onDelete = onDelete
        let resolvedId = entry?.id ?? UUID().uuidString
        self.id = resolvedId
        self.storedSecret = entry == nil ? "" : GitVault.secret(id: resolvedId)
        _host = State(initialValue: entry?.host ?? "")
        _kind = State(initialValue: entry?.kind ?? .token)
        _username = State(initialValue: entry?.username ?? "")
        _note = State(initialValue: entry?.note ?? "")
        _enabled = State(initialValue: entry?.enabled ?? true)
    }

    var body: some View {
        Form {
            Section {
                TextField(AppLocalized("github.com, *.corp.example or *"), text: $host)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Toggle(AppLocalized("Enabled"), isOn: $enabled)
                Picker(AppLocalized("Type"), selection: $kind) {
                    Text(AppLocalized("HTTPS token")).tag(GitVaultKind.token)
                    Text(AppLocalized("SSH key")).tag(GitVaultKind.sshKey)
                }
            } footer: {
                Text(AppLocalized("Exact host, *.wildcard for its subdomains, or * for any host. Avoid *: the secret would then be sent to every host a tool names, including one the model was talked into reading."))
            }

            Section {
                TextField(isSsh ? "git" : "git, oauth2 or x-token-auth", text: $username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text(AppLocalized("Username (optional)"))
            } footer: {
                Text(AppLocalized("Left empty, the username is picked per host: oauth2 for GitLab, x-token-auth for a Bitbucket app password, git for the rest."))
            }

            Section {
                ZStack(alignment: .topLeading) {
                    if reveal {
                        TextEditor(text: $secret)
                            .font(.system(.footnote, design: .monospaced))
                            .frame(minHeight: isSsh ? 160 : 60)
                    } else {
                        SecureField(secretPlaceholder, text: $secret)
                            .font(.system(.footnote, design: .monospaced))
                    }
                }
                Button {
                    reveal.toggle()
                } label: {
                    Label(
                        reveal ? AppLocalized("Hide") : AppLocalized("Show"),
                        systemImage: reveal ? "eye.slash" : "eye"
                    )
                }
                if isSsh {
                    SecureField(AppLocalized("Leave empty for an unencrypted key"), text: $passphrase)
                }
            } header: {
                Text(isSsh ? AppLocalized("Private key") : AppLocalized("Token"))
            } footer: {
                Text(AppLocalized("Kept in the app's encrypted store, never shown in full again, and never written into a transcript."))
            }

            Section {
                TextField(AppLocalized("work GitLab"), text: $note)
            } header: {
                Text(AppLocalized("Note (optional)"))
            }

            if !isNew {
                Section {
                    Button(role: .destructive) {
                        onDelete()
                        dismiss()
                    } label: {
                        Text(AppLocalized("Delete"))
                    }
                }
            }
        }
        .navigationTitle(isNew ? AppLocalized("Add credential") : AppLocalized("Edit credential"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalized("Cancel")) { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalized("Save")) { save() }
                    .disabled(normalizedHost.isEmpty)
            }
        }
    }

    /// Plain interpolation for the masked value: the secret is the point of the line,
    /// so it must render even with no catalog entry for the pattern.
    private var secretPlaceholder: String {
        storedSecret.isEmpty
            ? "Paste here"
            : "Stored: \(GitVault.masked(storedSecret)) — type to replace"
    }

    private func save() {
        guard !normalizedHost.isEmpty else { return }
        onSave(
            GitVaultEntry(
                id: id,
                host: normalizedHost,
                kind: kind,
                username: username.trimmingCharacters(in: .whitespaces),
                note: note.trimmingCharacters(in: .whitespaces),
                enabled: enabled
            ),
            // A new entry always writes its secret (empty clears it); an existing one
            // only when the user typed.
            (isNew || !secret.isEmpty) ? secret : nil,
            (isSsh && (isNew || !passphrase.isEmpty)) ? passphrase : nil
        )
        dismiss()
    }
}
