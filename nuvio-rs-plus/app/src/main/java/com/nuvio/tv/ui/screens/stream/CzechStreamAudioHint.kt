package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream
import java.util.Locale

/**
 * Fast, best-effort CZ-audio hint for the manual stream picker.
 *
 * Uses only metadata already returned by addons/debrid resolvers; no probing, network requests,
 * player initialization or filesystem access. This is intentionally independent of AutoPlay
 * and of the player's actual audio-track selection.
 */
internal object CzechStreamAudioHint {
    private const val MAX_TEXT_LENGTH = 640

    // Avoid matching CZ inside words such as "czechia" or URLs; we never inspect URLs.
    // Standalone CS is accepted only as structured language metadata or with an audio qualifier.
    private val czechReleaseMarker = Regex(
        """(?<![\p{L}\p{N}])(?:cz(?:[./_+-]sk)?|czsk|cze|ces|czech|cesky|česky|cestina|čeština)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )
    private val subtitleAfterLanguage = Regex(
        """(?<![\p{L}\p{N}])(?:cz(?:[./_+-]sk)?|czsk|cs|cze|ces|czech|cesky|česky|cestina|čeština)[\s._/+:()\-]{0,12}(?:sub(?:s|title|titles)?|tit|titulky|srt|vtt)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )
    private val subtitleBeforeLanguage = Regex(
        """(?<![\p{L}\p{N}])(?:sub(?:s|title|titles)?|tit|titulky|srt|vtt)(?:[\s._/+:()\-]{1,8}in)?[\s._/+:()\-]{1,12}(?:cz(?:[./_+-]sk)?|czsk|cs|cze|ces|czech|cesky|česky|cestina|čeština)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )
    private val explicitCzechAudio = Regex(
        """(?<![\p{L}\p{N}])(?:cz(?:[./_+-]sk)?|czsk|cs|cze|ces|czech|cesky|česky|český|cesky|cestina|čeština)[\s._/+:()\-]{0,14}(?:audio|dabing|dubbing|dubbed|dub|zvuk)(?![\p{L}\p{N}])|(?<![\p{L}\p{N}])(?:audio|dabing|dubbing|dubbed|dub|zvuk)[\s._/+:()\-]{1,14}(?:cz(?:[./_+-]sk)?|czsk|cs|cze|ces|czech|cesky|česky|český|cestina|čeština)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )

    fun isLikelyCzechAudio(stream: Stream): Boolean {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        // Do not use addonName, countryWhitelist, subtitle languages, audioDelayLanguage or URLs.
        val candidates = listOfNotNull(
            raw?.filename,
            resolve?.filename,
            stream.behaviorHints?.filename,
            stream.debridCacheStatus?.cachedName,
            raw?.torrentName,
            resolve?.torrentName,
            raw?.parsed?.rawTitle,
            stream.name,
            stream.title,
            stream.description
        )
        return fromHints(raw?.parsed?.languages, candidates)
    }

    /** Internal pure entry point used by tests; inexpensive enough to run per visible card. */
    internal fun fromHints(parsedLanguages: List<String>?, candidates: List<String>): Boolean {
        val names = candidates.asSequence().filter { it.isNotBlank() }
            .take(12).map { it.take(MAX_TEXT_LENGTH) }.toList()
        // CZ SUB/CZ TIT/Czech subtitles must never count as Czech *audio*.
        val withoutSubtitleTags = names.map { stripCzechSubtitleMarkers(it) }

        if (withoutSubtitleTags.any(explicitCzechAudio::containsMatchIn)) return true

        val subtitleOnlyMention = names.indices.any { names[it] != withoutSubtitleTags[it] }
        val structuredCzech = parsedLanguages.orEmpty().any(::isCzechLanguage)
        if (structuredCzech && !subtitleOnlyMention) return true

        return withoutSubtitleTags.any(czechReleaseMarker::containsMatchIn)
    }

    private fun stripCzechSubtitleMarkers(text: String): String =
        subtitleBeforeLanguage.replace(subtitleAfterLanguage.replace(text, " "), " ")

    private fun isCzechLanguage(language: String): Boolean {
        val value = language.trim().lowercase(Locale.ROOT).replace('_', '-')
        return value == "cs" || value == "cs-cz" || value == "cz" ||
            value == "cze" || value == "ces" || value == "czech" ||
            value == "cesky" || value == "česky" || value == "čeština" ||
            value == "cestina" || value == "cz/sk" || value == "czsk"
    }
}
