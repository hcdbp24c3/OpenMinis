package com.openminis.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.debounceSearchText
import com.openminis.app.ui.components.MinisAlertDialog
import androidx.compose.foundation.layout.PaddingValues

private const val SHEET_TAG = "ManageModelsSheet"

/**
 * Pure search filter for ManageProviderModelsSheet. Matches model id OR
 * display name case-insensitively; a blank query returns the full catalog
 * uncapped. Top-level and side-effect free so the sheet can memoize it from
 * remember without re-filtering on every recomposition.
 */
fun filterManageModels(entries: List<ModelEntry>, query: String): List<ModelEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    val lower = needle.lowercase()
    return entries.filter { entry ->
        entry.model.id.lowercase().contains(lower) ||
            entry.model.displayName.lowercase().contains(lower)
    }
}

/**
 * ModalBottomSheet listing the FULL catalog of a provider. Opened from the
 * single "Models (N)" row on ProviderDetailScreen. Search is debounced 250ms
 * via [debounceSearchText] so a ~2000-entry filter runs at most once per pause.
 *
 * Rows: displayName + id, eye toggle → optimistic local flip + async persist
 * (updateEntryAsync; the sync Room+JSON write used to jank the tap), tap →
 * onModelEntryClick(entry.id) WITHOUT dismissing, so the sheet reopens after
 * returning from the model-edit screen. Long-press menu (Delete) only for
 * isCustom entries — Hide/Show lives exclusively on the eye toggle now.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ManageProviderModelsSheet(
    instanceId: String,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onModelEntryClick: (String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val instance = remember(instanceId) { config.instances.firstOrNull { it.id == instanceId } }
    if (instance == null) {
        // Provider deleted while the sheet was open -> just close.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val rawEntries = remember(instanceId, config) { providerRepository.entriesFor(instanceId) }

    // [T-android-eye-toggle-jank] Optimistic hidden state: the eye click flips
    // the row alpha/icon on THIS frame; the durable Room+JSON double-write runs
    // off-thread via updateEntryAsync. Without this, the synchronous write under
    // configLock blocked the main thread before the config StateFlow could emit,
    // so the icon only changed after a visible stall. Overrides are dropped once
    // the persisted config catches up, keeping raw config the source of truth.
    var hiddenOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(rawEntries) {
        if (hiddenOverrides.isEmpty()) return@LaunchedEffect
        hiddenOverrides = hiddenOverrides.filter { (id, flipped) ->
            rawEntries.firstOrNull { it.id == id }?.isHidden != flipped
        }
    }
    val allEntries = remember(rawEntries, hiddenOverrides) {
        rawEntries.map { e ->
            val flipped = hiddenOverrides[e.id]
            if (flipped != null && flipped != e.isHidden) e.copy(isHidden = flipped) else e
        }
    }

    // [T-fix-manage-sheet-reopen] rememberSaveable pins the keyword to the
    // NavBackStackEntry so a model-edit-and-return restores the sheet's search.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        debouncedQuery = debounceSearchText(searchQuery)
    }
    val filtered = remember(debouncedQuery, allEntries) {
        filterManageModels(allEntries, debouncedQuery)
    }

    var menuEntryId by remember { mutableStateOf<String?>(null) }
    var entryToDelete by remember { mutableStateOf<ModelEntry?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // [T-fix-sheet-bottom-clip] The height constraint must live on a wrapping
        // Column INSIDE the sheet content, NOT on the ModalBottomSheet modifier.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
        ) {
            // Header: title + count, Close action.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_detail_manage_models_sheet_title, allEntries.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.common_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // 42dp search capsule — BasicTextField + DecorationBox (same
            // pattern as the chat picker). A plain OutlinedTextField forced
            // to height(42) clips its own 16dp contentPadding and the text
            // disappears on focus; owning contentPadding is the only way to
            // keep the frame short without cutting the text.
            val searchInteraction = remember { MutableInteractionSource() }
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = searchInteraction,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = searchQuery,
                        visualTransformation = VisualTransformation.None,
                        innerTextField = innerTextField,
                        placeholder = {
                            Text(stringResource(R.string.model_picker_search_placeholder))
                        },
                        label = null,
                        // [T-android-manage-search-parity] Leading Search icon +
                        // default colors: the Manage sheet's field is now the
                        // exact twin of the chat "Choose Model" search (which
                        // reads as the correct size); the missing icon and the
                        // custom border colors were what made this one look
                        // smaller/different.
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.model_picker_search_clear),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        enabled = true,
                        isError = false,
                        interactionSource = searchInteraction,
                        colors = OutlinedTextFieldDefaults.colors(),
                        // Zero vertical: the 42dp frame plus the icons already
                        // give the text room; any inset here re-clips it.
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        container = {
                            OutlinedTextFieldDefaults.Container(
                                enabled = true,
                                isError = false,
                                interactionSource = searchInteraction,
                                colors = OutlinedTextFieldDefaults.colors(),
                                shape = RoundedCornerShape(50),
                            )
                        },
                    )
                },
            )

            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.model_picker_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    itemsIndexed(filtered, key = { _, entry -> entry.id }) { index, entry ->
                        ManageModelRow(
                            entry = entry,
                            menuEntryId = menuEntryId,
                            onMenuEntryIdChange = { menuEntryId = it },
                            // [T-fix-manage-sheet-reopen] Do NOT call
                            // onDismiss here: navigating to the model-edit
                            // screen disposes the sheet; rememberSaveable on
                            // the host reopens it (with the search keyword)
                            // when the user pops back.
                            onClick = { onModelEntryClick(entry.id) },
                            onHideToggle = { target ->
                                // Flip locally FIRST (fast, same frame), then
                                // persist off the UI thread — see [T-android-eye-toggle-jank].
                                val newHidden = !target.isHidden
                                hiddenOverrides = hiddenOverrides + (target.id to newHidden)
                                AppLogger.info(
                                    SHEET_TAG,
                                    "Toggled ${target.model.displayName} hidden=$newHidden",
                                )
                                providerRepository.updateEntryAsync(target.copy(isHidden = newHidden))
                            },
                            onDeleteRequest = { entryToDelete = it },
                        )
                        if (index < filtered.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }

    // Same confirm path as the old inline list: removeEntry after confirm.
    entryToDelete?.let { e ->
        MinisAlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = stringResource(R.string.provider_detail_delete_model),
            text = stringResource(R.string.provider_detail_delete_model_confirm, e.model.displayName),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeEntry(e.id)
                AppLogger.info(SHEET_TAG, "Deleted model entry ${e.id} (${e.model.displayName})")
                entryToDelete = null
            },
        )
    }
}

@Composable
private fun ManageModelRow(
    entry: ModelEntry,
    menuEntryId: String?,
    onMenuEntryIdChange: (String?) -> Unit,
    onClick: () -> Unit,
    onHideToggle: (ModelEntry) -> Unit,
    onDeleteRequest: (ModelEntry) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (entry.isHidden) Modifier.alpha(0.45f) else Modifier)
            // Long-press menu is Delete-only now, and Delete only exists for
            // custom entries — so non-custom rows get a plain click (no dead
            // long-press that used to show a redundant Hide/Show item; the eye
            // toggle already covers hidden state).
            .then(
                if (entry.isCustom) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = { onMenuEntryIdChange(entry.id) },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { onHideToggle(entry) }) {
                Icon(
                    imageVector = if (entry.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (entry.isHidden) R.string.provider_detail_show_model
                        else R.string.provider_detail_hide_model,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = menuEntryId == entry.id,
            onDismissRequest = { onMenuEntryIdChange(null) },
        ) {
            // Hide/Show menu item removed on purpose: the eye IconButton on the
            // same row already toggles hidden state, and showing it twice
            // (long-press + eye) confused which control was authoritative.
            if (entry.isCustom) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.common_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        onMenuEntryIdChange(null)
                        onDeleteRequest(entry)
                    },
                )
            }
        }
    }
}
