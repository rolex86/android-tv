package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

internal object PlusWhatDidTheySayPolicy {
    const val REWIND_MS = 10_000L
    const val EXTRA_SUBTITLE_MS = 5_000L

    fun rewindTarget(currentPositionMs: Long): Long =
        (currentPositionMs.coerceAtLeast(0L) - REWIND_MS).coerceAtLeast(0L)

    fun restoreAtPosition(originalPositionMs: Long): Long =
        originalPositionMs.coerceAtLeast(0L) + EXTRA_SUBTITLE_MS
}

private sealed interface PlusSubtitleSnapshot {
    data object Disabled : PlusSubtitleSnapshot

    data class Internal(
        val trackId: String?,
        val language: String?,
        val name: String,
    ) : PlusSubtitleSnapshot

    data class Addon(
        val id: String,
        val url: String,
        val language: String,
        val addonName: String,
    ) : PlusSubtitleSnapshot
}

/**
 * Plus-only "What did they say?" action.
 *
 * It reuses Nuvio's existing seek and subtitle-selection APIs, does not persist the temporary
 * subtitle choice, and restores the previous runtime selection after playback passes the point
 * that triggered the action. If the user changes subtitles manually in the meantime, restoration
 * is skipped so Plus never fights an explicit user choice.
 */
internal fun PlayerRuntimeController.runPlusWhatDidTheySay() {
    if (_playbackTimeline.value.isLive) return

    val originalPositionMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: return
    val rewindTargetMs = PlusWhatDidTheySayPolicy.rewindTarget(originalPositionMs)
    val streamUrl = currentStreamUrl
    val previousSelection = snapshotPlusSubtitleSelection()

    plusWhatDidTheySayJob?.cancel()
    plusWhatDidTheySayJob = null

    seekPlaybackTo(rewindTargetMs, SeekParameters.PREVIOUS_SYNC)
    updatePlaybackTimeline(currentPosition = rewindTargetMs)
    scheduleProgressSyncAfterSeek()
    showSeekOverlayTemporarily()

    val temporarySignature = selectPlusTemporaryPreferredSubtitle() ?: return
    val restoreAtPositionMs = PlusWhatDidTheySayPolicy.restoreAtPosition(originalPositionMs)

    plusWhatDidTheySayJob = scope.launch {
        while (isActive && currentStreamUrl == streamUrl && !_uiState.value.playbackEnded) {
            delay(250L)
            val currentPosition = currentPlaybackPositionMs() ?: continue
            if (currentPosition >= restoreAtPositionMs) break
        }
        if (!isActive || currentStreamUrl != streamUrl || _uiState.value.playbackEnded) return@launch

        // Do not overwrite a subtitle choice the user made while the temporary subtitle was active.
        if (currentPlusSubtitleSignature() != temporarySignature) return@launch
        restorePlusSubtitleSelection(previousSelection)
    }
}

private fun PlayerRuntimeController.snapshotPlusSubtitleSelection(): PlusSubtitleSnapshot {
    val state = _uiState.value
    state.selectedAddonSubtitle?.let { subtitle ->
        return PlusSubtitleSnapshot.Addon(
            id = subtitle.id,
            url = subtitle.url,
            language = subtitle.lang,
            addonName = subtitle.addonName,
        )
    }
    state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.let { track ->
        return PlusSubtitleSnapshot.Internal(
            trackId = track.trackId,
            language = track.language,
            name = track.name,
        )
    }
    return PlusSubtitleSnapshot.Disabled
}

private fun PlayerRuntimeController.selectPlusTemporaryPreferredSubtitle(): String? {
    val state = _uiState.value
    val targets = subtitleLanguageTargets()
    if (targets.isEmpty()) return null

    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = targets,
        forcedOnly = false,
        normalOnly = true,
        selectedAudioTrack = null,
    )
    if (internalIndex >= 0) {
        val track = state.subtitleTracks[internalIndex]
        selectSubtitleTrack(internalIndex)
        _uiState.update {
            it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null)
        }
        return plusInternalSubtitleSignature(track)
    }

    val addon = targets.firstNotNullOfOrNull { target ->
        state.addonSubtitles.firstOrNull { subtitle ->
            !plusAddonSubtitleIsForced(subtitle) &&
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
        }
    } ?: return null

    selectAddonSubtitle(addon)
    return plusAddonSubtitleSignature(addon)
}

private fun PlayerRuntimeController.restorePlusSubtitleSelection(snapshot: PlusSubtitleSnapshot) {
    when (snapshot) {
        PlusSubtitleSnapshot.Disabled -> disableSubtitles()
        is PlusSubtitleSnapshot.Internal -> {
            val tracks = _uiState.value.subtitleTracks
            val index = tracks.indexOfFirst { track ->
                when {
                    !snapshot.trackId.isNullOrBlank() && track.trackId == snapshot.trackId -> true
                    else -> track.name == snapshot.name &&
                        snapshot.language?.let { language ->
                            PlayerSubtitleUtils.matchesLanguageCode(track.language, language)
                        } == true
                }
            }
            if (index >= 0) {
                selectSubtitleTrack(index)
                _uiState.update {
                    it.copy(selectedSubtitleTrackIndex = index, selectedAddonSubtitle = null)
                }
            } else {
                disableSubtitles()
            }
        }
        is PlusSubtitleSnapshot.Addon -> {
            val addon = _uiState.value.addonSubtitles.firstOrNull { candidate ->
                candidate.id == snapshot.id &&
                    candidate.url == snapshot.url &&
                    candidate.addonName == snapshot.addonName
            } ?: _uiState.value.addonSubtitles.firstOrNull { candidate ->
                candidate.id == snapshot.id &&
                    PlayerSubtitleUtils.matchesLanguageCode(candidate.lang, snapshot.language)
            }
            if (addon != null) {
                selectAddonSubtitle(addon)
            } else {
                disableSubtitles()
            }
        }
    }
}

private fun PlayerRuntimeController.currentPlusSubtitleSignature(): String {
    val state = _uiState.value
    state.selectedAddonSubtitle?.let { return plusAddonSubtitleSignature(it) }
    state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.let {
        return plusInternalSubtitleSignature(it)
    }
    return "off"
}

private fun plusInternalSubtitleSignature(track: TrackInfo): String =
    "internal:${track.trackId.orEmpty()}:${track.language.orEmpty()}:${track.name}"

private fun plusAddonSubtitleSignature(subtitle: Subtitle): String =
    "addon:${subtitle.id}:${subtitle.url}:${subtitle.addonName}"


private fun plusAddonSubtitleIsForced(subtitle: Subtitle): Boolean =
    listOf(subtitle.id, subtitle.url, subtitle.addonName).any {
        it.contains("forced", ignoreCase = true)
    }
