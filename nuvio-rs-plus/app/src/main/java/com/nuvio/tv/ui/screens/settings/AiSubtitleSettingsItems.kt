@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.aisubtitles.AiSubtitlePreferences
import com.nuvio.tv.ui.theme.NuvioTheme

internal fun LazyListScope.aiSubtitleSettingsItems(enabled: Boolean) {
    item(key = "plus_ai_subtitles_enabled") {
        val context = LocalContext.current
        AiSubtitlePreferences.ensureLoaded(context)
        val checked by AiSubtitlePreferences.enabled.collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.Sync,
            title = stringResource(R.string.ai_subtitle_setting_title),
            subtitle = stringResource(R.string.ai_subtitle_setting_description),
            isChecked = checked,
            onCheckedChange = { AiSubtitlePreferences.setEnabled(context, it) },
            enabled = enabled,
        )
    }
    item(key = "plus_ai_subtitles_backend") {
        val context = LocalContext.current
        AiSubtitlePreferences.ensureLoaded(context)
        val backend by AiSubtitlePreferences.backendUrl.collectAsStateWithLifecycle()
        var editing by remember { mutableStateOf(false) }
        NavigationSettingsItem(
            icon = Icons.Default.BugReport,
            title = stringResource(R.string.ai_subtitle_backend_title),
            subtitle = backend,
            onClick = { editing = true },
            enabled = enabled,
        )
        if (editing) AiSubtitleTextEntryDialog(
            title = stringResource(R.string.ai_subtitle_backend_title),
            initialValue = backend,
            password = false,
            onConfirm = {
                AiSubtitlePreferences.setBackendUrl(context, it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
    item(key = "plus_ai_subtitles_token") {
        val context = LocalContext.current
        AiSubtitlePreferences.ensureLoaded(context)
        val token by AiSubtitlePreferences.apiToken.collectAsStateWithLifecycle()
        var editing by remember { mutableStateOf(false) }
        NavigationSettingsItem(
            icon = Icons.Default.QrCode2,
            title = stringResource(R.string.ai_subtitle_token_title),
            subtitle = if (token.isBlank()) stringResource(R.string.ai_subtitle_token_optional)
                       else stringResource(R.string.ai_subtitle_token_configured),
            onClick = { editing = true },
            enabled = enabled,
        )
        if (editing) AiSubtitleTextEntryDialog(
            title = stringResource(R.string.ai_subtitle_token_title),
            initialValue = token,
            password = true,
            onConfirm = {
                AiSubtitlePreferences.setApiToken(context, it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun AiSubtitleTextEntryDialog(
    title: String,
    initialValue: String,
    password: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val focus = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 760.dp, suppressFirstKeyUp = false) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth()
                    .padding(NuvioTheme.spacing.md)
                    .focusRequester(focus),
                textStyle = TextStyle(
                    color = NuvioTheme.colors.TextPrimary,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize
                ),
                cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = { onConfirm(value) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
}
