package com.openminis.app.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.gitvault.GitVault
import com.openminis.app.data.gitvault.GitVaultEntry
import com.openminis.app.data.gitvault.GitVaultKind
import com.openminis.app.data.gitvault.GitVaultMaterializer
import com.openminis.app.data.gitvault.GitVaultSyncResult
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.SettingsRowDivider
import java.util.UUID

/**
 * [T-git-vault] Settings › Git vault: every git credential the app holds, matched to
 * a host, and the projection of them into the sandbox.
 *
 * Shape follows the rest of settings: a list inside [SettingsScaffold], and every
 * edit on a full page rather than a dialog — a private key is multi-line and a token
 * paste is long, so a dialog would be unusable, and the destructive action needs room
 * to be deliberate.
 *
 * Secret handling: an existing secret is never displayed. The field shows
 * [GitVault.masked] as its placeholder and only replaces the stored value when the
 * user actually types, so opening an entry to fix its host cannot blank a working
 * token.
 */
@Composable
fun GitVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(GitVault.entries(context)) }
    // null = list; "" = adding; otherwise the id being edited.
    var editingId by remember { mutableStateOf<String?>(null) }
    var syncResult by remember { mutableStateOf<GitVaultSyncResult?>(null) }
    var syncNote by remember { mutableStateOf<String?>(null) }

    val rootfs = remember { RootfsManager.getInstance(context) }
    val editing = entries.firstOrNull { it.id == editingId }

    /**
     * Push the vault into the sandbox after every change, so the terminal picks up a
     * new key without a second, forgettable step. Skipped before the rootfs exists:
     * writing into `<files>/alpine-rootfs/root` first would race the installer, which
     * deletes that whole directory when it starts.
     */
    fun syncNow() {
        syncNote = null
        if (!rootfs.isInstalled) {
            syncResult = null
            syncNote = context.getString(R.string.git_vault_sync_no_rootfs)
            return
        }
        syncResult = GitVaultMaterializer.sync(context, rootfs.rootfsDir)
    }

    LaunchedEffect(Unit) {
        // One-shot migration of the four token fields this app used to keep for
        // repo_digest, then a sync so the sandbox reflects the vault on entry.
        GitVault.importRepoDigestLegacyOnce(context)
        entries = GitVault.entries(context)
        syncNow()
    }

    BackHandler(enabled = editingId != null) { editingId = null }

    if (editingId != null) {
        GitVaultEditor(
            entry = editing,
            onCancel = { editingId = null },
            onSave = { updated, secret, passphrase ->
                if (secret != null) GitVault.setSecret(context, updated.id, secret)
                if (passphrase != null) GitVault.setPassphrase(context, updated.id, passphrase)
                GitVault.save(context, updated)
                entries = GitVault.entries(context)
                editingId = null
                syncNow()
            },
            onDelete = editing?.let {
                {
                    GitVault.delete(context, it.id)
                    entries = GitVault.entries(context)
                    editingId = null
                    syncNow()
                }
            },
        )
        return
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_git_vault),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.git_vault_header),
            footer = stringResource(R.string.git_vault_footer),
        ) {
            if (entries.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.git_vault_empty_title),
                    subtitle = stringResource(R.string.git_vault_empty_subtitle),
                    onClick = { editingId = "" },
                    showDivider = false,
                    minHeight = 72.dp,
                )
            } else {
                entries.forEach { entry ->
                    SettingsRow(
                        title = entry.host,
                        subtitle = subtitleFor(context, entry),
                        iconColor = if (entry.enabled) Color(0xFF34C759) else MaterialTheme.colorScheme.outline,
                        onClick = { editingId = entry.id },
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.git_vault_add),
                    icon = Icons.Outlined.Add,
                    iconColor = MaterialTheme.colorScheme.primary,
                    onClick = { editingId = "" },
                    showDivider = false,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.git_vault_sandbox_header),
            footer = syncFooter(context, syncResult, syncNote),
        ) {
            SettingsRow(
                title = stringResource(R.string.git_vault_sync),
                subtitle = stringResource(R.string.git_vault_sync_subtitle),
                icon = Icons.Outlined.Sync,
                iconColor = Color(0xFF0A84FF),
                onClick = { syncNow() },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun subtitleFor(context: Context, entry: GitVaultEntry): String {
    val kind = stringResource(
        if (entry.kind == GitVaultKind.SSH_KEY) R.string.git_vault_kind_ssh else R.string.git_vault_kind_token,
    )
    val stored = if (GitVault.hasSecret(context, entry.id)) {
        GitVault.masked(GitVault.secretFor(context, entry.id))
    } else {
        stringResource(R.string.git_vault_no_secret)
    }
    val note = entry.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    val state = if (entry.enabled) "" else " · ${stringResource(R.string.git_vault_disabled)}"
    return "$kind · $stored$note$state"
}

@Composable
private fun syncFooter(context: Context, result: GitVaultSyncResult?, note: String?): String {
    if (note != null) return note
    val r = result ?: return stringResource(R.string.git_vault_sync_subtitle)
    val lines = mutableListOf(context.getString(R.string.git_vault_sync_done, r.tokens, r.keys, r.home))
    if (r.skipped > 0) lines += context.getString(R.string.git_vault_sync_skipped, r.skipped)
    if (r.wildcards > 0) lines += context.getString(R.string.git_vault_sync_wildcards, r.wildcards)
    return lines.joinToString("\n")
}

/**
 * The add/edit page. [entry] null means "new".
 *
 * Once a secret is stored, the field starts empty with the masked value as its
 * placeholder and [onSave] receives null for "unchanged" — typing replaces it, and
 * clearing it is only possible for a new entry (there is a Delete for the other case).
 */
@Composable
private fun GitVaultEditor(
    entry: GitVaultEntry?,
    onCancel: () -> Unit,
    onSave: (GitVaultEntry, String?, String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val isNew = entry == null
    val id = remember(entry?.id) { entry?.id ?: UUID.randomUUID().toString() }
    val storedSecret = remember(id, entry) { if (isNew) "" else GitVault.secretFor(context, id) }
    val hasStored = storedSecret.isNotEmpty()

    var host by remember { mutableStateOf(entry?.host.orEmpty()) }
    var kind by remember { mutableStateOf(entry?.kind ?: GitVaultKind.TOKEN) }
    var username by remember { mutableStateOf(entry?.username.orEmpty()) }
    var note by remember { mutableStateOf(entry?.note.orEmpty()) }
    var enabled by remember { mutableStateOf(entry?.enabled ?: true) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    val isSsh = kind == GitVaultKind.SSH_KEY
    val normalizedHost = GitVault.normalizePattern(host)

    fun save() {
        if (normalizedHost.isEmpty()) return
        onSave(
            GitVaultEntry(
                id = id,
                host = normalizedHost,
                kind = kind,
                username = username.trim(),
                note = note.trim(),
                enabled = enabled,
            ),
            // A new entry always writes its secret (empty clears it); an existing one
            // only when the user typed.
            if (isNew || secret.isNotEmpty()) secret else null,
            if (isSsh && (isNew || passphrase.isNotEmpty())) passphrase else null,
        )
    }

    SettingsScaffold(
        title = stringResource(if (isNew) R.string.git_vault_add else R.string.git_vault_edit),
        onBack = onCancel,
        actions = {
            TextButton(onClick = { save() }, enabled = normalizedHost.isNotEmpty()) {
                Text(stringResource(R.string.common_save))
            }
        },
    ) {
        SettingsSection(footer = stringResource(R.string.git_vault_host_footer)) {
            LabeledField(
                label = stringResource(R.string.git_vault_host),
                value = host,
                placeholder = stringResource(R.string.git_vault_host_placeholder),
                onValueChange = { host = it },
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.git_vault_enabled),
                checked = enabled,
                onCheckedChange = { enabled = it },
                showDivider = true,
            )
            KindRow(selectedIsSsh = isSsh, onSelect = { kind = it })
        }

        SettingsSection(footer = stringResource(R.string.git_vault_username_footer)) {
            LabeledField(
                label = stringResource(R.string.git_vault_username),
                value = username,
                placeholder = if (isSsh) "git" else "git / oauth2 / x-token-auth",
                onValueChange = { username = it },
                divider = false,
            )
        }

        SettingsSection(footer = stringResource(R.string.git_vault_secret_footer)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(if (isSsh) R.string.git_vault_private_key else R.string.git_vault_token),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                DialogTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (hasStored) {
                        stringResource(R.string.git_vault_secret_stored, GitVault.masked(storedSecret))
                    } else {
                        stringResource(R.string.git_vault_secret_placeholder)
                    },
                    singleLine = false,
                    maxLines = if (isSsh) 12 else 3,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
            if (isSsh) {
                SettingsRowDivider()
                LabeledField(
                    label = stringResource(R.string.git_vault_passphrase),
                    value = passphrase,
                    placeholder = stringResource(R.string.git_vault_passphrase_placeholder),
                    onValueChange = { passphrase = it },
                    secret = true,
                    divider = false,
                )
            }
        }

        SettingsSection {
            LabeledField(
                label = stringResource(R.string.git_vault_note),
                value = note,
                placeholder = stringResource(R.string.git_vault_note_placeholder),
                onValueChange = { note = it },
                divider = false,
            )
        }

        if (onDelete != null) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.common_delete),
                    icon = Icons.Outlined.Delete,
                    iconColor = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                    showDivider = false,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Two mutually exclusive choices, with the selected one ticked. */
@Composable
private fun KindRow(selectedIsSsh: Boolean, onSelect: (GitVaultKind) -> Unit) {
    Column {
        SettingsRow(
            title = stringResource(R.string.git_vault_kind_token),
            subtitle = stringResource(R.string.git_vault_kind_token_subtitle),
            trailing = if (selectedIsSsh) null else {
                { Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF34C759)) }
            },
            onClick = { onSelect(GitVaultKind.TOKEN) },
            showDivider = true,
        )
        SettingsRow(
            title = stringResource(R.string.git_vault_kind_ssh),
            subtitle = stringResource(R.string.git_vault_kind_ssh_subtitle),
            trailing = if (selectedIsSsh) {
                { Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF34C759)) }
            } else {
                null
            },
            onClick = { onSelect(GitVaultKind.SSH_KEY) },
            showDivider = false,
        )
    }
}

/** Label above a [DialogTextField], the shape the other settings screens use. */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
    divider: Boolean = true,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        DialogTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
    if (divider) SettingsRowDivider()
}
