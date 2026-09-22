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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.debounceSearchText
import com.openminis.app.ui.components.MinisAlertDialog

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
 * Rows: displayName + id, eye toggle → updateEntry(isHidden), long-press →
 * Hide/Show + Delete (isCustom only) with the same confirm path as the old
 * inline list, tap → onDismiss + onModelEntryClick(entry.id).
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
    val allEntries = remember(instanceId, config) { providerRepository.entriesFor(instanceId) }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
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

            // 42dp search capsule.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(21.dp),
                placeholder = {
                    Text(
                        text = stringResource(R.string.model_picker_search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.model_picker_search_clear),
                            )
                        }
                    }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
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
                            onClick = {
                                onDismiss()
                                onModelEntryClick(entry.id)
                            },
                            onHideToggle = { target ->
                                providerRepository.updateEntry(target.copy(isHidden = !target.isHidden))
                                AppLogger.info(
                                    SHEET_TAG,
                                    "Toggled ${target.model.displayName} hidden=${!target.isHidden}",
                                )
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onMenuEntryIdChange(entry.id) },
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
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (entry.isHidden) R.string.provider_detail_show_model
                            else R.string.provider_detail_hide_model,
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        if (entry.isHidden) Icons.Filled.Visibility
                        else Icons.Filled.VisibilityOff,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onMenuEntryIdChange(null)
                    onHideToggle(entry)
                },
            )
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
