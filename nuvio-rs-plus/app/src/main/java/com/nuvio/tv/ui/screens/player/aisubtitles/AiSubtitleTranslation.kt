package com.nuvio.tv.ui.screens.player.aisubtitles

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSyncedSubtitle
import com.nuvio.tv.ui.screens.player.commitPreparedSidecarSubtitle
import com.nuvio.tv.ui.screens.player.currentSidecarGenerationFor
import com.nuvio.tv.ui.screens.player.parseSidecarTimedCuesRobust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.WeakHashMap

internal enum class AiSubtitleTranslationPhase {
    IDLE,
    WAITING_FOR_SYNC,
    TRANSLATING,
    READY,
    FAILED,
}

internal data class AiSubtitleTranslationState(
    val phase: AiSubtitleTranslationPhase = AiSubtitleTranslationPhase.IDLE,
    val progress: Int = 0,
    val background: Boolean = false,
    val sourceUrl: String? = null,
    val readySubtitle: Subtitle? = null,
    val selectedAutomatically: Boolean = false,
    val message: String? = null,
)

internal object AiSubtitleTranslationStatus {
    private val mutableState = MutableStateFlow(AiSubtitleTranslationState())
    val state: StateFlow<AiSubtitleTranslationState> = mutableState.asStateFlow()

    internal fun set(value: AiSubtitleTranslationState) {
        mutableState.value = value
    }

    internal fun update(block: (AiSubtitleTranslationState) -> AiSubtitleTranslationState) {
        mutableState.update(block)
    }
}

private data class TranslationSession(
    val job: Job,
    val client: AiSubtitleBackendClient,
    val backendUrl: String,
    val apiToken: String,
    val pausedPlayer: ExoPlayer?,
    val resumePlaybackAfterBackground: Boolean,
)

private object AiSubtitleSessions {
    val sessions = WeakHashMap<PlayerRuntimeController, TranslationSession>()
}

internal fun PlayerRuntimeController.startAiSubtitleTranslation() {
    AiSubtitlePreferences.ensureLoaded(context)
    if (!AiSubtitlePreferences.enabled.value) return
    if (AiSubtitleSessions.sessions[this]?.job?.isActive == true) return
    if (_uiState.value.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
        AiSubtitleTranslationStatus.set(
            AiSubtitleTranslationState(
                phase = AiSubtitleTranslationPhase.FAILED,
                message = "AI translation currently requires ExoPlayer",
            )
        )
        return
    }

    val selected = _uiState.value.selectedAddonSubtitle ?: run {
        AiSubtitleTranslationStatus.set(
            AiSubtitleTranslationState(
                phase = AiSubtitleTranslationPhase.FAILED,
                message = "Select an add-on subtitle first",
            )
        )
        return
    }
    if (isAiSubtitle(selected)) return

    val player = _exoPlayer
    val resumePlaybackAfterBackground = player?.playWhenReady == true
    if (resumePlaybackAfterBackground) {
        player.pause()
    }

    val backendUrl = AiSubtitlePreferences.backendUrl.value
    val apiToken = AiSubtitlePreferences.apiToken.value
    val client = AiSubtitleBackendClient()

    AiSubtitleTranslationStatus.set(
        AiSubtitleTranslationState(
            phase = AiSubtitleTranslationPhase.WAITING_FOR_SYNC,
            sourceUrl = selected.url,
        )
    )

    val job = scope.launch {
        try {
            val snapshot = captureAiSubtitleSnapshot(selected)
                ?: throw IllegalStateException("The selected subtitle is not available as a synced SRT/VTT sidecar")

            AiSubtitleTranslationStatus.update {
                it.copy(
                    phase = AiSubtitleTranslationPhase.TRANSLATING,
                    progress = 0,
                    sourceUrl = snapshot.sourceUrl,
                    message = null,
                )
            }

            val result = client.translate(
                backendUrl = backendUrl,
                apiToken = apiToken,
                job = AiSubtitleJob(
                    subtitleText = snapshot.srt,
                    sourceFormat = "srt",
                    sourceLanguage = snapshot.sourceLanguage,
                    targetLanguage = AiSubtitlePreferences.TARGET_LANGUAGE,
                    title = contentName ?: title,
                    contentType = contentType,
                    contentId = contentId,
                    season = currentSeason,
                    episode = currentEpisode,
                    clientVersion = BuildConfig.VERSION_NAME,
                ),
                onProgress = { progress ->
                    AiSubtitleTranslationStatus.update { current ->
                        current.copy(
                            phase = AiSubtitleTranslationPhase.TRANSLATING,
                            progress = progress,
                        )
                    }
                },
            )

            val aiSubtitle = AiSubtitleFileStore.store(context, result)
            val translatedCues = parseSidecarTimedCuesRobust(result.subtitleText, aiSubtitle.url).cues
            val parsed = mergeTranslatedTextOntoTiming(snapshot.cues, translatedCues)
                ?: throw IllegalStateException("Translated subtitle cue structure changed")

            _uiState.update { state ->
                state.copy(
                    addonSubtitles = (state.addonSubtitles + aiSubtitle)
                        .distinctBy { subtitle -> subtitle.id + "|" + subtitle.url },
                )
            }

            val sourceStillSelected =
                _uiState.value.selectedAddonSubtitle?.url == snapshot.sourceUrl &&
                    activeSidecarSubtitleKey == snapshot.sourceUrl &&
                    currentSidecarGenerationFor(snapshot.sourceUrl) == snapshot.generation

            val selectedAutomatically = sourceStillSelected &&
                commitPreparedSidecarSubtitle(
                    expectedCurrentUrl = snapshot.sourceUrl,
                    newUrl = aiSubtitle.url,
                    cues = parsed,
                    expectedGeneration = snapshot.generation,
                )

            if (selectedAutomatically) {
                _uiState.update {
                    it.copy(
                        selectedAddonSubtitle = aiSubtitle,
                        selectedSubtitleTrackIndex = -1,
                    )
                }
                AutoSyncSyncedSubtitle.mark(aiSubtitle.url)
            }

            AiSubtitleTranslationStatus.set(
                AiSubtitleTranslationState(
                    phase = AiSubtitleTranslationPhase.READY,
                    progress = 100,
                    background = AiSubtitleTranslationStatus.state.value.background,
                    sourceUrl = snapshot.sourceUrl,
                    readySubtitle = aiSubtitle,
                    selectedAutomatically = selectedAutomatically,
                )
            )
        } catch (cancel: CancellationException) {
            AiSubtitleTranslationStatus.set(AiSubtitleTranslationState())
            throw cancel
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "AI subtitle translation failed", error)
            val message = when (error) {
                is AiSubtitleException -> when (error.failure) {
                    AiSubtitleFailure.TIMEOUT -> "AI subtitle translation timed out"
                    AiSubtitleFailure.UNAVAILABLE -> "AI subtitle translator is unavailable"
                    AiSubtitleFailure.UNAUTHORIZED -> "AI subtitle translator rejected the token"
                    AiSubtitleFailure.INVALID_REQUEST -> "AI subtitle translator configuration is invalid"
                    AiSubtitleFailure.TOO_LARGE -> "Subtitle is too large to translate"
                    AiSubtitleFailure.TRANSLATION_FAILED -> "AI subtitle translation failed"
                }
                else -> error.message ?: "AI subtitle translation failed"
            }
            AiSubtitleTranslationStatus.set(
                AiSubtitleTranslationState(
                    phase = AiSubtitleTranslationPhase.FAILED,
                    background = AiSubtitleTranslationStatus.state.value.background,
                    sourceUrl = selected.url,
                    message = message,
                )
            )
        } finally {
            synchronized(AiSubtitleSessions.sessions) {
                AiSubtitleSessions.sessions.remove(this@startAiSubtitleTranslation)
            }
        }
    }

    synchronized(AiSubtitleSessions.sessions) {
        AiSubtitleSessions.sessions[this] = TranslationSession(
            job = job,
            client = client,
            backendUrl = backendUrl,
            apiToken = apiToken,
            pausedPlayer = player,
            resumePlaybackAfterBackground = resumePlaybackAfterBackground,
        )
    }
}

internal fun PlayerRuntimeController.setAiSubtitleTranslationBackground(background: Boolean) {
    AiSubtitleTranslationStatus.update { it.copy(background = background) }
    if (!background) return

    onEvent(PlayerEvent.OnDismissTransientOverlay)

    val session = synchronized(AiSubtitleSessions.sessions) {
        AiSubtitleSessions.sessions[this]
    } ?: return
    val player = session.pausedPlayer ?: return
    if (
        session.resumePlaybackAfterBackground &&
        _exoPlayer === player &&
        !player.playWhenReady
    ) {
        player.play()
    }
}

internal fun PlayerRuntimeController.cancelAiSubtitleTranslation() {
    val session = synchronized(AiSubtitleSessions.sessions) {
        AiSubtitleSessions.sessions.remove(this)
    } ?: return
    scope.launch {
        runCatching { session.client.cancelActive(session.backendUrl, session.apiToken) }
        session.job.cancel()
    }
}
