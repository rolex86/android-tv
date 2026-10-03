package com.nuvio.tv.ui.screens.player.aisubtitles

import androidx.media3.common.C
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.currentSidecarGenerationFor
import com.nuvio.tv.ui.screens.player.audiosync.SubtitleSyncDiagnostics
import com.nuvio.tv.ui.screens.player.audiosync.SubtitleSyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class AiSubtitleSnapshot(
    val sourceUrl: String,
    val sourceLanguage: String?,
    val srt: String,
    val cues: List<CuesWithTiming>,
    val cueCount: Int,
    val generation: Long,
)

/**
 * Waits for Nuvio RS AutoSync (and its audio fallback, when active), then snapshots the exact
 * sidecar timeline currently being rendered. Translation therefore changes text only; it never
 * has to run AutoSync again.
 */
internal suspend fun PlayerRuntimeController.captureAiSubtitleSnapshot(
    requestedSubtitle: Subtitle,
): AiSubtitleSnapshot? {
    automaticSubtitleSyncJob?.join()

    withTimeoutOrNull(AUDIO_SYNC_SETTLE_TIMEOUT_MS) {
        SubtitleSyncStatus.diagnostics.first { diagnostics ->
            diagnostics == null ||
                diagnostics.phase == SubtitleSyncDiagnostics.Phase.Synced ||
                diagnostics.phase == SubtitleSyncDiagnostics.Phase.Unavailable
        }
    }

    val selected = _uiState.value.selectedAddonSubtitle ?: return null
    if (selected.url != requestedSubtitle.url) return null

    val sourceUrl = selected.url
    val expectedGeneration = currentSidecarGenerationFor(sourceUrl) ?: return null
    val cues = withTimeoutOrNull(SIDECAR_READY_TIMEOUT_MS) {
        while (
            activeSidecarSubtitleKey == sourceUrl &&
            currentSidecarGenerationFor(sourceUrl) == expectedGeneration &&
            sidecarTimedCues.isEmpty()
        ) {
            delay(SIDECAR_READY_POLL_MS)
        }
        sidecarTimedCues.takeIf {
            activeSidecarSubtitleKey == sourceUrl &&
                currentSidecarGenerationFor(sourceUrl) == expectedGeneration &&
                it.isNotEmpty()
        }
    } ?: return null

    val srt = cues.toSrt()
    if (srt.isBlank() || srt.toByteArray(Charsets.UTF_8).size > MAX_SRT_BYTES) return null

    return AiSubtitleSnapshot(
        sourceUrl = sourceUrl,
        sourceLanguage = selected.lang.takeIf { it.isNotBlank() },
        srt = srt,
        cues = cues,
        cueCount = cues.size,
        generation = expectedGeneration,
    )
}

internal fun mergeTranslatedTextOntoTiming(
    timing: List<CuesWithTiming>,
    translated: List<CuesWithTiming>,
): List<CuesWithTiming>? {
    if (timing.size != translated.size || timing.isEmpty()) return null

    return timing.indices.map { index ->
        val original = timing[index]
        val translatedEntry = translated[index]
        if (original.startTimeUs == C.TIME_UNSET) return null

        val durationUs = when {
            original.durationUs != C.TIME_UNSET -> original.durationUs
            original.endTimeUs != C.TIME_UNSET -> original.endTimeUs - original.startTimeUs
            else -> C.TIME_UNSET
        }

        CuesWithTiming(
            translatedEntry.cues,
            original.startTimeUs,
            durationUs,
        )
    }
}

internal fun List<CuesWithTiming>.toSrt(): String {
    val out = StringBuilder(size * 64)
    var number = 1

    for (entry in this) {
        if (entry.startTimeUs == C.TIME_UNSET) continue
        val startUs = entry.startTimeUs.coerceAtLeast(0L)
        val endUs = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
            entry.durationUs != C.TIME_UNSET -> startUs + entry.durationUs
            else -> startUs + 1_000L
        }.coerceAtLeast(startUs + 1_000L)

        val text = entry.cues
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .distinct()
            .joinToString("\n")
            .trim()
        if (text.isBlank()) continue

        out.append(number++).append('\n')
        out.append(formatSrtTime(startUs / 1_000L))
            .append(" --> ")
            .append(formatSrtTime(endUs / 1_000L))
            .append('\n')
        out.append(text).append("\n\n")
    }

    return out.toString()
}

private fun formatSrtTime(msValue: Long): String {
    var ms = msValue.coerceAtLeast(0L)
    val hours = ms / 3_600_000L
    ms %= 3_600_000L
    val minutes = ms / 60_000L
    ms %= 60_000L
    val seconds = ms / 1_000L
    val millis = ms % 1_000L
    return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
}

private const val SIDECAR_READY_TIMEOUT_MS = 10_000L
private const val SIDECAR_READY_POLL_MS = 50L
private const val AUDIO_SYNC_SETTLE_TIMEOUT_MS = 30_000L
private const val MAX_SRT_BYTES = 2 * 1024 * 1024
