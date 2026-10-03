package com.nuvio.tv.ui.screens.player.aisubtitles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun AiSubtitleTranslateButton(
    state: AiSubtitleTranslationState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val running = state.phase == AiSubtitleTranslationPhase.WAITING_FOR_SYNC ||
        state.phase == AiSubtitleTranslationPhase.TRANSLATING
    Button(
        onClick = onClick,
        enabled = enabled && !running,
    ) {
        Text(
            text = if (running) {
                stringResource(R.string.ai_subtitle_progress_short, state.progress)
            } else {
                stringResource(R.string.ai_subtitle_translate_action)
            }
        )
    }
}

@Composable
internal fun AiSubtitleTranslationDialog(
    state: AiSubtitleTranslationState,
    onBackground: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state.background) return
    if (
        state.phase != AiSubtitleTranslationPhase.WAITING_FOR_SYNC &&
        state.phase != AiSubtitleTranslationPhase.TRANSLATING
    ) return

    val waiting = state.phase == AiSubtitleTranslationPhase.WAITING_FOR_SYNC
    NuvioDialog(
        onDismiss = onBackground,
        title = stringResource(R.string.ai_subtitle_dialog_title),
        subtitle = if (waiting) {
            stringResource(R.string.ai_subtitle_waiting_for_sync)
        } else {
            stringResource(R.string.ai_subtitle_progress, state.progress)
        },
        width = 620.dp,
        suppressFirstKeyUp = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                LoadingIndicator(modifier = Modifier.size(42.dp))
            }
            Text(
                text = if (waiting) {
                    stringResource(R.string.ai_subtitle_waiting_detail)
                } else {
                    stringResource(R.string.ai_subtitle_translating_detail)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            ) {
                Button(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = onBackground, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_subtitle_continue_background))
                }
            }
        }
    }
}
