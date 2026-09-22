import SwiftUI

/// [T-ios-manage-provider-models] Sheet listing the FULL model catalog for one
/// provider with per-row visibility toggles. Opened from the single
/// "Models (N)" row on ProviderInstanceDetailView (mirrors the Android /
/// RikkaMinis ManageProviderModelsSheet).
///
/// Search is debounced (250ms) so the ~2000-entry fuzzy filter runs at most
/// once per pause instead of on every keystroke. `List` provides row
/// virtualization for large catalogs. Tap → edit detail via `onSelectEntry`.
/// Per-model delete for `isCustom` entries is reachable via the row context
/// menu (mirrors the old inline trash + confirm). Eye button toggles `isHidden`.
struct ManageProviderModelsSheet: View {
    let instanceId: String
    /// Host presents `ModelEntryDetailSheet` when a row is tapped.
    var onSelectEntry: (ModelEntry) -> Void
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = ProviderConfigStore.shared

    @State private var searchText = ""
    /// [T-ios-manage-provider-models] Debounced copy of `searchText` (250ms).
    /// The field binds to `searchText` for immediate feedback, but the
    /// entry filter reads this debounced value so it runs at most once per
    /// pause instead of on every keystroke.
    @State private var debouncedSearchText = ""
    @State private var pendingDeleteModelEntry: ModelEntry?

    private var allEntries: [ModelEntry] {
        store.entries(for: instanceId)
    }

    private var filteredEntries: [ModelEntry] {
        // [T-ios-manage-provider-models] Default (no query) keeps the
        // release-rank order from `entries(for:)`. While searching, skip the
        // expensive rank comparator and filter raw storage-order entries —
        // search results are relevance-filtered, not ranked.
        guard !debouncedSearchText.isEmpty else { return allEntries }
        return store.rawEntries(for: instanceId).filter { entry in
            fuzzyMatch(query: debouncedSearchText, text: entry.model.displayName)
                || fuzzyMatch(query: debouncedSearchText, text: entry.model.id)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchField

                Divider()

                if filteredEntries.isEmpty {
                    Text(debouncedSearchText.isEmpty
                         ? AppLocalized("No models")
                         : AppLocalized("No matching models"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 32)
                } else {
                    List(filteredEntries) { entry in
                        modelRow(entry)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(AppLocalized("All Models (\(allEntries.count))"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalized("Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task(id: searchText) {
            // [T-ios-manage-provider-models] Debounce the search field: each
            // keystroke cancels the previous sleep (`.task(id:)` restarts the
            // task when `searchText` changes), so the entry filter runs at
            // most once per pause instead of on every keystroke.
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            debouncedSearchText = searchText
        }
        .alert(
            AppLocalized("Delete Model"),
            isPresented: Binding(
                get: { pendingDeleteModelEntry != nil },
                set: { if !$0 { pendingDeleteModelEntry = nil } }
            ),
            presenting: pendingDeleteModelEntry
        ) { entry in
            Button("Delete", role: .destructive) {
                store.removeEntry(entry.id)
                pendingDeleteModelEntry = nil
            }
            Button("Cancel", role: .cancel) {
                pendingDeleteModelEntry = nil
            }
        } message: { entry in
            Text("Are you sure you want to delete \"\(entry.model.displayName)\"? This action cannot be undone.")
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField(AppLocalized("Search models"), text: $searchText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private func modelRow(_ entry: ModelEntry) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(entry.model.displayName)
                        .font(.subheadline)
                        .foregroundStyle(entry.isHidden ? .secondary : .primary)
                    if entry.isCustom {
                        Text(AppLocalized("Custom"))
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.orange)
                    }
                }
                Text(entry.model.id)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Button {
                var updated = entry
                updated.isHidden = !entry.isHidden
                store.updateEntry(updated)
            } label: {
                Image(systemName: entry.isHidden ? "eye.slash" : "eye")
                    .font(.caption)
                    .foregroundStyle(entry.isHidden ? .tertiary : .secondary)
            }
            .buttonStyle(.plain)
            .frame(minWidth: 22, minHeight: 22)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            onSelectEntry(entry)
            dismiss()
        }
        .contextMenu {
            // Per-model delete stays reachable for custom entries — mirrors the
            // old modelEntryRow trash + pendingDeleteModelEntry confirm.
            if entry.isCustom {
                Button(role: .destructive) {
                    pendingDeleteModelEntry = entry
                } label: {
                    Label(AppLocalized("Delete"), systemImage: "trash")
                }
            }
            Button {
                UIPasteboard.general.string = "entry:\(entry.compositeKey)"
                MinisToast.show(AppLocalized("Copied: \(entry.model.displayName)"))
            } label: {
                Label(AppLocalized("Copy Shortcut Model ID"), systemImage: "link")
            }
        }
    }
}
