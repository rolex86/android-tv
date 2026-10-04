package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.data.local.SmartAudioContentPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAudioFilteringTest {
    @Test
    fun commentaryTrackIsSkippedWhenNormalTrackExists() {
        val tracks = listOf(
            track(0, "English Commentary", "en", "audio/ac3", roleFlags = C.ROLE_FLAG_COMMENTARY),
            track(1, "English", "en", "audio/ac3"),
        )
        assertEquals(1, select(tracks))
    }

    @Test
    fun audioDescriptionTrackIsSkippedWhenNormalTrackExists() {
        val tracks = listOf(
            track(0, "English Audio Description", "en", "audio/eac3", roleFlags = C.ROLE_FLAG_DESCRIBES_VIDEO),
            track(1, "English", "en", "audio/ac3"),
        )
        assertEquals(1, select(tracks))
    }

    @Test
    fun filtersFallBackWhenEverySupportedTrackIsFiltered() {
        val tracks = listOf(
            track(0, "Director Commentary", "en", "audio/ac3", roleFlags = C.ROLE_FLAG_COMMENTARY),
        )
        assertEquals(0, select(tracks))
    }

    @Test
    fun dubbedPreferenceOutranksLanguageOrder() {
        val tracks = listOf(
            track(0, "English Original", "en", "audio/true-hd"),
            track(1, "Czech Dubbed", "cs", "audio/ac3", roleFlags = C.ROLE_FLAG_DUB),
        )
        assertEquals(
            1,
            SmartAudioFiltering.findPreferredTrackIndex(
                tracks = tracks,
                preferredLanguages = listOf("en", "cs"),
                ignoreCommentary = true,
                ignoreAudioDescription = true,
                preferBestQuality = true,
                contentPreference = SmartAudioContentPreference.DUBBED,
                allowMediaDefault = false,
            )
        )
    }

    @Test
    fun originalPreferenceRecognizesCzechLabels() {
        val original = track(0, "Původní znění", "en", "audio/ac3")
        val dubbed = track(1, "Český dabing", "cs", "audio/true-hd")
        assertTrue(SmartAudioFiltering.isExplicitOriginal(original))
        assertTrue(SmartAudioFiltering.isDubbed(dubbed))
        assertEquals(
            0,
            SmartAudioFiltering.findPreferredTrackIndex(
                tracks = listOf(original, dubbed),
                preferredLanguages = listOf("cs", "en"),
                ignoreCommentary = true,
                ignoreAudioDescription = true,
                preferBestQuality = true,
                contentPreference = SmartAudioContentPreference.ORIGINAL,
                allowMediaDefault = false,
            )
        )
    }

    @Test
    fun qualityRankingPrefersTrueHdWithinSameLanguage() {
        val ac3 = track(0, "English AC3", "en", "audio/ac3", channels = 6, bitrate = 640_000)
        val trueHd = track(1, "English TrueHD", "en", "audio/true-hd", channels = 8, bitrate = 4_000_000)
        assertEquals(1, select(listOf(ac3, trueHd)))
    }

    @Test
    fun preferredLanguageBeatsHigherQualityOtherLanguage() {
        val czech = track(0, "Czech AC3", "cs", "audio/ac3", channels = 6)
        val english = track(1, "English TrueHD", "en", "audio/true-hd", channels = 8)
        assertEquals(
            0,
            SmartAudioFiltering.findPreferredTrackIndex(
                tracks = listOf(czech, english),
                preferredLanguages = listOf("cs", "en"),
                ignoreCommentary = true,
                ignoreAudioDescription = true,
                preferBestQuality = true,
                contentPreference = SmartAudioContentPreference.LANGUAGE,
                allowMediaDefault = false,
            )
        )
    }

    @Test
    fun unsupportedHighQualityTrackDoesNotWin() {
        val unsupported = track(0, "English TrueHD", "en", "audio/true-hd", supported = false)
        val supported = track(1, "English AC3", "en", "audio/ac3")
        assertEquals(1, select(listOf(unsupported, supported)))
        assertFalse(supported.isSelected)
    }

    private fun select(tracks: List<TrackInfo>): Int =
        SmartAudioFiltering.findPreferredTrackIndex(
            tracks = tracks,
            preferredLanguages = listOf("en"),
            ignoreCommentary = true,
            ignoreAudioDescription = true,
            preferBestQuality = true,
            contentPreference = SmartAudioContentPreference.LANGUAGE,
            allowMediaDefault = false,
        )

    private fun track(
        index: Int,
        label: String,
        language: String,
        mime: String,
        roleFlags: Int = 0,
        channels: Int = 2,
        bitrate: Int = 192_000,
        supported: Boolean = true,
    ) = TrackInfo(
        index = index,
        name = label,
        language = language,
        codec = mime,
        channelCount = channels,
        rawLabel = label,
        sampleMimeType = mime,
        bitrate = bitrate,
        roleFlags = roleFlags,
        isSupported = supported,
    )
}
