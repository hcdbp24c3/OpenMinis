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
import com.openminis.app.ui.components.DialogTextField

/**
 * [T-android-repo-digest] Settings › Repository digest: the GitHub token
 * [com.openminis.app.tools.RepoDigestTool] uses.
 *
 * One field, because that is the whole configuration. Without a token the tool
 * still reads public repositories (60 API calls per hour, and only ONE of them is
 * needed per digest — the file contents come from the raw host, which is not part
 * of the API quota); with one it reaches private repositories and 5000 calls/hour.
 */
@Composable
fun RepoDigestSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(RepoDigestPrefs.token(context)) }

    SettingsScaffold(
        title = stringResource(R.string.settings_repo_digest),
        onBack = onBack,
    ) {
        SettingsSection(footer = stringResource(R.string.repo_digest_token_footer)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.repo_digest_token),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        RepoDigestPrefs.setToken(context, it)
                    },
                    placeholder = stringResource(R.string.repo_digest_token_placeholder),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}
