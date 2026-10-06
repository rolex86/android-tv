@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.home.PlusHomeFocusDiagnostics
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
internal fun PlusHomeFocusDiagnosticsSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareChooserTitle = stringResource(R.string.debug_home_focus_share_chooser)

    var enabled by remember(context) {
        mutableStateOf(PlusHomeFocusDiagnostics.isEnabled(context))
    }
    var showLogDialog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    SettingsGroupCard(
        title = stringResource(R.string.debug_home_focus_section),
        subtitle = stringResource(R.string.debug_home_focus_logging_subtitle)
    ) {
        SettingsToggleRow(
            title = stringResource(R.string.debug_home_focus_logging_title),
            subtitle = stringResource(R.string.debug_home_focus_logging_subtitle),
            checked = enabled,
            onToggle = {
                val newValue = !enabled
                PlusHomeFocusDiagnostics.setEnabled(context, newValue)
                enabled = newValue
            },
            expandSubtitleOnFocus = true
        )

        SettingsActionRow(
            title = stringResource(R.string.debug_home_focus_view_title),
            subtitle = stringResource(R.string.debug_home_focus_view_subtitle),
            onClick = {
                scope.launch {
                    logText = PlusHomeFocusDiagnostics.readLog(
                        context = context,
                        maxLines = 500,
                        newestFirst = true
                    )
                    showLogDialog = true
                }
            }
        )

        SettingsActionRow(
            title = stringResource(R.string.debug_home_focus_export_title),
            subtitle = stringResource(R.string.debug_home_focus_export_subtitle),
            onClick = {
                scope.launch {
                    runCatching {
                        PlusHomeFocusDiagnostics.exportToDownloads(context)
                    }.onSuccess { path ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.debug_home_focus_export_success, path),
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.debug_home_focus_export_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )

        SettingsActionRow(
            title = stringResource(R.string.debug_home_focus_share_title),
            subtitle = stringResource(R.string.debug_home_focus_share_subtitle),
            onClick = {
                scope.launch {
                    runCatching {
                        val sendIntent = PlusHomeFocusDiagnostics.createShareIntent(context)
                        val chooser = Intent.createChooser(sendIntent, shareChooserTitle)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(chooser)
                    }
                }
            }
        )

        SettingsActionRow(
            title = stringResource(R.string.debug_home_focus_clear_title),
            subtitle = stringResource(R.string.debug_home_focus_clear_subtitle),
            onClick = {
                scope.launch {
                    PlusHomeFocusDiagnostics.clear(context)
                    logText = ""
                }
            }
        )
    }

    if (showLogDialog) {
        PlusHomeFocusLogDialog(
            logText = logText,
            onDismiss = { showLogDialog = false }
        )
    }
}

@Composable
private fun PlusHomeFocusLogDialog(
    logText: String,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(logText) {
        runCatching { focusRequester.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debug_home_focus_dialog_title),
        subtitle = stringResource(R.string.debug_home_focus_dialog_subtitle),
        width = 920.dp,
        usePlatformDefaultWidth = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    val delta = when (event.key) {
                        Key.DirectionDown -> 320
                        Key.DirectionUp -> -320
                        else -> return@onPreviewKeyEvent false
                    }
                    val target = (scrollState.value + delta).coerceIn(0, scrollState.maxValue)
                    if (target == scrollState.value) {
                        false
                    } else {
                        scope.launch { scrollState.animateScrollTo(target) }
                        true
                    }
                }
                .verticalScroll(scrollState)
                .padding(horizontal = NuvioTheme.spacing.sm)
        ) {
            Text(
                text = logText.ifBlank { stringResource(R.string.debug_home_focus_log_empty) },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = NuvioTheme.colors.TextSecondary
            )
        }

        SettingsActionRow(
            title = stringResource(R.string.debug_dismiss),
            subtitle = null,
            onClick = onDismiss
        )
    }
}
