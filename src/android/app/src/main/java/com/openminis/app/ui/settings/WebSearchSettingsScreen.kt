package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
import com.openminis.app.tools.WebSearchSettings
import com.openminis.app.ui.components.DialogTextField

/**
 * [T-android-web-search] Settings › Web search: which backend `web_search` uses,
 * and its credentials.
 *
 * Ported from tall-1997/OpenMinis-Linux and extended to Kelivo's full provider
 * set. Engine NAMES come from [WebSearchSettings.Engine.displayName] rather than
 * one string resource per provider: they are product names ("Tavily", "Kagi",
 * "You.com"), so translating them would be 26 keys of nothing in 18 locales.
 *
 * Keyed backends take a BATCH of keys — one per line, or pasted from a column —
 * and [com.openminis.app.tools.SearchKeyRotator] rotates them per request. The
 * list below the field shows each key masked, so a user can see how many are
 * configured without the screen displaying any of them.
 */
@Composable
fun WebSearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(WebSearchSettings.engine(context)) }
    var searx by remember { mutableStateOf(WebSearchSettings.searxngUrl(context)) }
    var searxAuth by remember { mutableStateOf(WebSearchSettings.searxngAuth(context)) }
    var customUrl by remember { mutableStateOf(WebSearchSettings.customUrl(context)) }
    var customKey by remember { mutableStateOf(WebSearchSettings.customKey(context)) }
    var customHeader by remember { mutableStateOf(WebSearchSettings.customKeyHeader(context)) }
    var fallback by remember { mutableStateOf(WebSearchSettings.fallbackEnabled(context)) }
    var detail by remember { mutableStateOf<WebSearchSettings.Engine?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    val title = detail?.displayName ?: stringResource(R.string.settings_web_search)

    SettingsScaffold(
        title = title,
        // List is a first-level settings page (no back arrow). Engine
        // detail keeps the arrow to pop back to the list.
        onBack = if (detail != null) {
            { detail = null }
        } else {
            null
        },
    ) {
        val current = detail
        if (current == null) {
            SettingsSection(
                header = stringResource(R.string.web_search_engine_header),
                footer = stringResource(R.string.web_search_engine_footer),
            ) {
                WebSearchSettings.Engine.entries.forEachIndexed { index, item ->
                    EngineNavRow(
                        title = item.displayName,
                        subtitle = subtitleFor(context, item),
                        selected = engine == item,
                        showDivider = index < WebSearchSettings.Engine.entries.lastIndex,
                        onClick = { detail = item },
                    )
                }
            }

            SettingsSection(footer = stringResource(R.string.web_search_fallback_footer)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_search_fallback),
                    subtitle = stringResource(R.string.web_search_fallback_sub),
                    checked = fallback,
                    onCheckedChange = {
                        fallback = it
                        WebSearchSettings.setFallbackEnabled(context, it)
                    },
                    showDivider = false,
                )
            }
        } else {
            SettingsSection(footer = detailFooter(current)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_search_use_engine),
                    checked = engine == current,
                    onCheckedChange = { on ->
                        if (on) {
                            engine = current
                            WebSearchSettings.setEngine(context, current)
                        }
                    },
                    showDivider = current.needsKey,
                )
                if (current.needsKey) {
                    KeysEditor(current)
                }
            }

            when (current) {
                WebSearchSettings.Engine.SEARXNG -> SettingsSection {
                    CredentialField(
                        label = stringResource(R.string.web_search_searxng_url),
                        value = searx,
                        placeholder = "https://searx.example/search",
                    ) {
                        searx = it
                        WebSearchSettings.setSearxngUrl(context, it)
                    }
                    CredentialField(
                        label = stringResource(R.string.web_search_searxng_auth),
                        value = searxAuth,
                        placeholder = "user:password",
                    ) {
                        searxAuth = it
                        WebSearchSettings.setSearxngAuth(context, it)
                    }
                }
                WebSearchSettings.Engine.CUSTOM -> SettingsSection {
                    CredentialField(
                        label = stringResource(R.string.web_search_custom_url),
                        value = customUrl,
                        placeholder = "https://example.com/search?q={query}",
                    ) {
                        customUrl = it
                        WebSearchSettings.setCustomUrl(context, it)
                    }
                    CredentialField(
                        label = stringResource(R.string.web_search_custom_key),
                        value = customKey,
                        placeholder = stringResource(R.string.web_search_custom_key_placeholder),
                    ) {
                        customKey = it
                        WebSearchSettings.setCustomKey(context, it)
                    }
                    CredentialField(
                        label = stringResource(R.string.web_search_custom_key_header),
                        value = customHeader,
                        placeholder = "Authorization",
                    ) {
                        customHeader = it
                        WebSearchSettings.setCustomKeyHeader(context, it)
                    }
                }
                else -> {
                    // Endpoints the user may point elsewhere (Kelivo exposes the
                    // same fields): a self-hosted gateway, a proxy, a mirror.
                    val urlKey = current.urlKey
                    if (urlKey != null) {
                        var override by remember(current) {
                            mutableStateOf(current.urlOverride(context))
                        }
                        SettingsSection(footer = stringResource(R.string.web_search_url_override_footer)) {
                            CredentialField(
                                label = stringResource(R.string.web_search_url_override),
                                value = override,
                                placeholder = current.defaultUrl.orEmpty(),
                            ) {
                                override = it
                                WebSearchSettings.setUrlOverride(context, current, it)
                            }
                        }
                    }
                }
            }
        }
    }


}

/**
 * [T-android-web-search] Batch key editor: one field, one key per line (or any
 * whitespace/comma/semicolon), plus the masked list of what is stored.
 */
@Composable
private fun KeysEditor(engine: WebSearchSettings.Engine) {
    val context = LocalContext.current
    var raw by remember(engine) {
        mutableStateOf(WebSearchSettings.keys(context, engine).joinToString("\n"))
    }
    val stored = remember(raw) { WebSearchSettings.parseKeyBatch(raw) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.web_search_api_keys),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = raw,
            onValueChange = {
                raw = it
                WebSearchSettings.setKeys(context, engine, it)
            },
            placeholder = stringResource(R.string.web_search_api_key_placeholder),
            singleLine = false,
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        if (stored.size > 1) {
            Text(
                stringResource(R.string.web_search_keys_rotate, stored.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        stored.forEach { key ->
            Text(
                WebSearchSettings.maskKey(key),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun EngineNavRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        showChevron = true,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.web_search_in_use),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

/**
 * [T-android-web-search] Configured / not-configured line under an engine row,
 * and the footer on its detail page. File-level so they can be used from the
 * list body without being declared after their call site.
 */
@Composable
private fun subtitleFor(context: android.content.Context, item: WebSearchSettings.Engine): String = when {
    item == WebSearchSettings.Engine.DDG -> stringResource(R.string.web_search_no_key_needed)
    item == WebSearchSettings.Engine.BING_LOCAL -> stringResource(R.string.web_search_no_key_needed)
    item == WebSearchSettings.Engine.SEARXNG -> if (WebSearchSettings.searxngUrl(context).isBlank()) {
        stringResource(R.string.web_search_not_configured)
    } else {
        stringResource(R.string.web_search_configured)
    }
    item == WebSearchSettings.Engine.CUSTOM -> if (WebSearchSettings.customUrl(context).isBlank()) {
        stringResource(R.string.web_search_not_configured)
    } else {
        stringResource(R.string.web_search_configured)
    }
    else -> {
        val count = WebSearchSettings.keys(context, item).size
        when {
            count == 0 -> stringResource(R.string.web_search_not_configured)
            count == 1 -> stringResource(R.string.web_search_configured)
            // Multiple keys are the point of the rotation: say how many rather
            // than hiding the difference behind "Configured".
            else -> context.getString(R.string.web_search_keys_configured, count)
        }
    }
}

@Composable
private fun detailFooter(engine: WebSearchSettings.Engine): String = when (engine) {
    WebSearchSettings.Engine.DDG -> stringResource(R.string.web_search_ddg_detail)
    WebSearchSettings.Engine.BING_LOCAL -> stringResource(R.string.web_search_bing_local_detail)
    WebSearchSettings.Engine.SEARXNG -> stringResource(R.string.web_search_searxng_detail)
    WebSearchSettings.Engine.CUSTOM -> stringResource(R.string.web_search_custom_detail)
    else -> stringResource(R.string.web_search_keyed_detail, engine.displayName)
}
