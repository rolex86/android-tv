package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.data.local.SmartAudioContentPreference
import kotlinx.coroutines.flow.update
import java.text.Normalizer
import java.util.Locale

/**
 * Plus smart-audio policy ported from JustPlayer Plus.
 *
 * Existing Nuvio remembered/manual track selection always wins. This policy is only used when
 * no remembered audio choice was restored for the current stream.
 */
internal object SmartAudioFiltering {
    fun findPreferredTrackIndex(
        tracks: List<TrackInfo>,
        preferredLanguages: List<String>,
        ignoreCommentary: Boolean,
        ignoreAudioDescription: Boolean,
        preferBestQuality: Boolean,
        contentPreference: SmartAudioContentPreference,
        allowMediaDefault: Boolean,
    ): Int {
        if (tracks.isEmpty()) return -1

        val filtered = tracks.indices.filter { index ->
            val track = tracks[index]
            track.isSupported &&
                (!ignoreCommentary || !isCommentary(track)) &&
                (!ignoreAudioDescription || !isAudioDescription(track))
        }
        val candidates = if (filtered.isNotEmpty()) {
            filtered
        } else {
            // Fail safe: never filter away the only playable audio.
            tracks.indices.filter { tracks[it].isSupported }
        }
        if (candidates.isEmpty()) return -1

        val hasFlaggedDefault = candidates.any { index ->
            (tracks[index].selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
        }

        val comparator = Comparator<Int> { leftIndex, rightIndex ->
            val left = tracks[leftIndex]
            val right = tracks[rightIndex]

            compareValues(
                contentRank(left, contentPreference),
                contentRank(right, contentPreference)
            ).takeIf { it != 0 }
                ?: compareValues(
                    languageRank(left, preferredLanguages, allowMediaDefault, hasFlaggedDefault),
                    languageRank(right, preferredLanguages, allowMediaDefault, hasFlaggedDefault)
                ).takeIf { it != 0 }
                ?: if (preferBestQuality) {
                    compareValues(formatRank(left), formatRank(right)).takeIf { it != 0 }
                        ?: compareValues(right.channelCount ?: 0, left.channelCount ?: 0).takeIf { it != 0 }
                        ?: compareValues(right.bitrate ?: 0, left.bitrate ?: 0).takeIf { it != 0 }
                        ?: compareValues(leftIndex, rightIndex)
                } else {
                    compareValues(leftIndex, rightIndex)
                }
        }

        return candidates.minWithOrNull(comparator) ?: -1
    }

    internal fun isCommentary(track: TrackInfo): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) return true
        return containsAnyPhrase(
            normalizedLabel(track),
            "commentary",
            "director commentary",
            "cast commentary",
            "komentar",
        )
    }

    internal fun isAudioDescription(track: TrackInfo): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        return containsAnyPhrase(
            normalizedLabel(track),
            "audio description",
            "descriptive audio",
            "visually impaired",
            "described video",
            "audiodescription",
            "audiodeskripce",
        )
    }

    internal fun isDubbed(track: TrackInfo): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DUB) != 0) return true
        val label = normalizedLabel(track)
        if (Regex("(^|\\s)dub($|\\s)").containsMatchIn(label)) return true
        return containsAnyPhrase(
            label,
            "dubbed",
            "dubbing",
            "cz dub",
            "czech dub",
            "dabing",
            "dabovany",
            "cesky dabing",
            "synchronized",
            "synchronised",
        )
    }

    internal fun isExplicitOriginal(track: TrackInfo): Boolean {
        val label = normalizedLabel(track)
        if (Regex("(^|\\s)ov($|\\s)").containsMatchIn(label)) return true
        return containsAnyPhrase(
            label,
            "original",
            "original audio",
            "original language",
            "original version",
            "puvodni zneni",
            "puvodni audio",
        )
    }

    internal fun formatRank(track: TrackInfo): Int {
        val mime = track.sampleMimeType?.lowercase(Locale.ROOT).orEmpty()
        when (mime) {
            "audio/true-hd" -> return 0
            "audio/vnd.dts.hd" -> return 1
            "audio/eac3-joc" -> return 2
            "audio/eac3" -> return 3
            "audio/vnd.dts" -> return 4
            "audio/ac3" -> return 5
            "audio/flac" -> return 6
            "audio/opus" -> return 7
            "audio/mp4a-latm" -> return 8
        }

        val codec = normalize(track.codec)
        return when {
            codec.contains("truehd") || codec.contains("true hd") -> 0
            codec.contains("dts hd") || codec.contains("dtshd") -> 1
            codec.contains("eac3 joc") || codec.contains("e ac 3 joc") -> 2
            codec.contains("eac3") || codec.contains("e ac 3") ||
                codec.contains("dolby digital plus") -> 3
            codec.contains("dts") -> 4
            codec.contains("ac3") || codec.contains("ac 3") ||
                codec == "dolby digital" -> 5
            codec.contains("flac") -> 6
            codec.contains("opus") -> 7
            codec.contains("aac") || codec.contains("mp4a") -> 8
            else -> 20
        }
    }

    private fun contentRank(
        track: TrackInfo,
        preference: SmartAudioContentPreference,
    ): Int = when (preference) {
        SmartAudioContentPreference.LANGUAGE -> 0
        SmartAudioContentPreference.ORIGINAL -> when {
            isExplicitOriginal(track) -> 0
            isDubbed(track) -> 2
            else -> 1
        }
        SmartAudioContentPreference.DUBBED -> when {
            isDubbed(track) -> 0
            isExplicitOriginal(track) -> 2
            else -> 1
        }
    }

    private fun languageRank(
        track: TrackInfo,
        preferredLanguages: List<String>,
        allowMediaDefault: Boolean,
        hasFlaggedDefault: Boolean,
    ): Int {
        preferredLanguages.forEachIndexed { index, language ->
            if (audioTrackMatchesLanguage(track, language)) return index
        }

        if (allowMediaDefault) {
            val flaggedDefault = (track.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
            // MPV does not expose Media3 selection flags in TrackInfo. Before Plus applies an
            // override its selected track is MPV's own automatic/default choice.
            if (flaggedDefault || (!hasFlaggedDefault && track.isSelected)) {
                return preferredLanguages.size
            }
        }
        return UNMATCHED_LANGUAGE_RANK
    }

    private fun audioTrackMatchesLanguage(track: TrackInfo, target: String): Boolean {
        if (PlayerSubtitleUtils.matchesLanguageCode(track.language, target)) return true
        val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = track.language,
            name = track.rawLabel ?: track.name,
            trackId = track.trackId,
        )
        return PlayerSubtitleUtils.matchesLanguageCode(variant, target)
    }

    private fun normalizedLabel(track: TrackInfo): String =
        normalize(track.rawLabel ?: track.name)

    private fun containsAnyPhrase(value: String, vararg phrases: String): Boolean =
        phrases.any(value::contains)

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private const val UNMATCHED_LANGUAGE_RANK = 100
}

internal fun PlayerRuntimeController.tryApplySmartAudioSelection(
    audioTracks: List<TrackInfo>,
): Int? {
    if (audioTracks.isEmpty()) return null
    if (hasAppliedRememberedAudioSelection || rememberedTrackPreference?.audio != null) return null

    val index = SmartAudioFiltering.findPreferredTrackIndex(
        tracks = audioTracks,
        preferredLanguages = mpvPreferredAudioLanguages,
        ignoreCommentary = smartAudioIgnoreCommentarySetting,
        ignoreAudioDescription = smartAudioIgnoreAudioDescriptionSetting,
        preferBestQuality = smartAudioPreferBestQualitySetting,
        contentPreference = smartAudioContentPreferenceSetting,
        allowMediaDefault = smartAudioAllowMediaDefaultSetting,
    )
    if (index < 0) return null

    val selected = audioTracks.getOrNull(index) ?: return null
    val alreadySelected = selected.isSelected || _uiState.value.selectedAudioTrackIndex == index
    logSwitchTrace(
        stage = "smart-audio",
        message = "index=$index alreadySelected=$alreadySelected lang=${selected.language} " +
            "name=${selected.name} codec=${selected.codec} channels=${selected.channelCount} " +
            "content=$smartAudioContentPreferenceSetting bestQuality=$smartAudioPreferBestQualitySetting " +
            "ignoreCommentary=$smartAudioIgnoreCommentarySetting ignoreAD=$smartAudioIgnoreAudioDescriptionSetting"
    )

    if (!alreadySelected) {
        selectAudioTrack(index)
        _uiState.update { it.copy(selectedAudioTrackIndex = index) }
    }
    return index
}
