package com.openminis.app.ui.chat

import kotlinx.coroutines.delay

/**
 * Debounce a search query: returns [searchText] only after [debounceMillis] of
 * quiet. The pickers call this from a `LaunchedEffect(searchText)` so rapid
 * keystrokes cancel the previous delay and the ~2000-entry filter runs at most
 * once per pause.
 *
 * The chat model picker's own search now lives in
 * `ui/components/ModelSearch` (debounced, relevance-ranked, capped), and
 * `ui/settings/ManageProviderModelsSheet` owns [filterManageModels]; this
 * debounce is shared by that sheet.
 */
suspend fun debounceSearchText(
    searchText: String,
    debounceMillis: Long = 250,
): String {
    delay(debounceMillis)
    return searchText
}
