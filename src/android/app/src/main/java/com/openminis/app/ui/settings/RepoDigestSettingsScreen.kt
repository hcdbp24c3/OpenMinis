package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.gitvault.GitVault
import com.openminis.app.data.gitvault.GitVaultKind

/**
 * [T-android-repo-digest] Settings › Repository digest: how the tool authenticates,
 * and a way into the credential store.
 *
 * This screen used to own four token fields, one per forge family. That was wrong
 * for two reasons — a company GitLab and gitlab.com want different tokens, and there
 * was nowhere to put an SSH key — so credentials now live in the git vault, keyed by
 * HOST, and this page only reports what is configured. Editing happens on the vault
 * screen, pointed at with the count so the user can tell at a glance whether the tool
 * is authenticated.
 */
@Composable
fun RepoDigestSettingsScreen(onBack: () -> Unit, onOpenVault: () -> Unit) {
    val context = LocalContext.current
    val entries = GitVault.entries(context)
    val tokens = entries.count { it.kind == GitVaultKind.TOKEN && it.enabled }
    val keys = entries.count { it.kind == GitVaultKind.SSH_KEY && it.enabled }

    SettingsScaffold(
        title = stringResource(R.string.settings_repo_digest),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.repo_digest_credentials_header),
            footer = stringResource(R.string.repo_digest_credentials_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_git_vault),
                subtitle = when {
                    entries.isEmpty() -> stringResource(R.string.git_vault_row_none)
                    keys == 0 -> stringResource(R.string.git_vault_row_tokens, tokens)
                    tokens == 0 -> stringResource(R.string.git_vault_row_keys, keys)
                    else -> stringResource(R.string.git_vault_row_both, tokens, keys)
                },
                icon = Icons.Outlined.Key,
                iconColor = Color(0xFF34C759),
                onClick = onOpenVault,
                showDivider = false,
            )
        }
    }
}
