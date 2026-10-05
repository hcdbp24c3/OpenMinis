package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.RepoDigestPrefs
import com.openminis.app.tools.RepoDigestTool
import com.openminis.app.ui.components.DialogTextField

/**
 * [T-android-repo-digest] Settings › Repository digest: the per-host git tokens
 * [RepoDigestTool] uses.
 *
 * One field per host family, because the auth schemes genuinely differ — GitHub
 * and Bitbucket take a Bearer token, GitLab wants `PRIVATE-TOKEN`, Gitea/Forgejo
 * expect `token …` — so a single field would either be sent with a header some
 * hosts reject or invite pasting a credential into the wrong slot.
 *
 * All of them are optional: public repositories on every supported host work
 * without any token, and the tool says which host it recognised in its output.
 */
@Composable
fun RepoDigestSettingsScreen(onBack: () -> Unit) {
    SettingsScaffold(
        title = stringResource(R.string.settings_repo_digest),
        onBack = onBack,
    ) {
        SettingsSection(footer = stringResource(R.string.repo_digest_tokens_footer)) {
            TokenField(
                provider = RepoDigestTool.Provider.GITHUB,
                label = stringResource(R.string.repo_digest_token),
                placeholder = stringResource(R.string.repo_digest_token_placeholder),
                showDivider = true,
            )
            TokenField(
                provider = RepoDigestTool.Provider.GITLAB,
                label = stringResource(R.string.repo_digest_token_gitlab),
                placeholder = stringResource(R.string.repo_digest_token_placeholder),
                showDivider = true,
            )
            TokenField(
                provider = RepoDigestTool.Provider.GITEA,
                label = stringResource(R.string.repo_digest_token_gitea),
                placeholder = stringResource(R.string.repo_digest_token_placeholder),
                showDivider = true,
            )
            TokenField(
                provider = RepoDigestTool.Provider.BITBUCKET,
                label = stringResource(R.string.repo_digest_token_bitbucket),
                placeholder = stringResource(R.string.repo_digest_token_placeholder),
                showDivider = false,
            )
        }
    }
}

@Composable
private fun TokenField(
    provider: RepoDigestTool.Provider,
    label: String,
    placeholder: String,
    showDivider: Boolean,
) {
    val context = LocalContext.current
    var value by remember(provider) { mutableStateOf(RepoDigestPrefs.token(context, provider)) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = value,
            onValueChange = {
                value = it
                RepoDigestPrefs.setToken(context, provider, it)
            },
            placeholder = placeholder,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        if (showDivider) {
            // A hairline keeps four near-identical fields readable as four
            // different hosts rather than one long form.
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
        }
    }
}
