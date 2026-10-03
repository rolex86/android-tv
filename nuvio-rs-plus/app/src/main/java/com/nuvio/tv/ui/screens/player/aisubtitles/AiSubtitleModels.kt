package com.nuvio.tv.ui.screens.player.aisubtitles

internal data class AiSubtitleJob(
    val subtitleText: String,
    val sourceFormat: String,
    val sourceLanguage: String?,
    val targetLanguage: String,
    val title: String?,
    val contentType: String?,
    val contentId: String?,
    val season: Int?,
    val episode: Int?,
    val clientVersion: String,
)

internal data class AiSubtitleResult(
    val cacheKey: String,
    val outputFormat: String,
    val language: String,
    val label: String,
    val subtitleText: String,
    val cached: Boolean,
)

internal enum class AiSubtitleFailure {
    TIMEOUT, UNAVAILABLE, UNAUTHORIZED, INVALID_REQUEST, TOO_LARGE, TRANSLATION_FAILED
}

internal class AiSubtitleException(
    val failure: AiSubtitleFailure,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
