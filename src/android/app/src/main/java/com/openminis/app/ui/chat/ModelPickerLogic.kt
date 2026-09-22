package com.openminis.app.ui.chat

import com.openminis.app.data.model.ModelEntry
import kotlinx.coroutines.delay

/**
 * Order the picker's per-provider entries. The default unfiltered list is
 * release-ranked newest-first so the picker never opens on a stale model;
 * while a search is active the re-sort is skipped — search results keep their
 * natural order and we avoid an O(n log n) sort of ~2000 entries on every
 * keystroke.
 */
fun orderPickerEntries(
    entries: List<ModelEntry>,
    searching: Boolean,
    releaseRank: Comparator<ModelEntry>,
): List<ModelEntry> =
    if (searching) entries else entries.sortedWith(releaseRank)

/**
 * Debounce a search query: returns [searchText] only after [debounceMillis] of
 * quiet. The pickers call this from a `LaunchedEffect(searchText)` so rapid
 * keystrokes cancel the previous delay and the ~2000-entry filter runs at most
 * once per pause.
 */
suspend fun debounceSearchText(
    searchText: String,
    debounceMillis: Long = 250,
): String {
    delay(debounceMillis)
    return searchText
}

/**
 * Precise model search match (Kelivo semantics): case-insensitive contiguous
 * substring only. No subsequence/fuzzy fallback — `deepseek-flash` must NOT
 * match `deepseek-v4-flash` (chars in order across an interruption).
 *
 * Pure and top-level so pickers can memoize it and unit tests can pin it.
 */
fun matchesModelQuery(text: String, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return text.contains(needle, ignoreCase = true)
}