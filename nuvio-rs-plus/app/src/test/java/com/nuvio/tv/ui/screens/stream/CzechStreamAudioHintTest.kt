package com.nuvio.tv.ui.screens.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CzechStreamAudioHintTest {
    private fun probable(vararg names: String, languages: List<String>? = null): Boolean =
        CzechStreamAudioHint.fromHints(languages, names.toList())

    @Test fun czechReleaseMarkers() {
        assertTrue(probable("Scooby.Doo.S02E01.CZ.1080p.WEB-DL.mkv"))
        assertTrue(probable("Scooby-Doo 1080p CZE DABING"))
        assertTrue(probable("Scooby-Doo [CZ/SK] 720p"))
        assertTrue(probable("Scooby Doo český dabing WEB-DL"))
        assertTrue(probable("film_ces_audio_x265.mkv"))
        assertTrue(probable("Scooby Doo CZDAB 1080p".replace("CZDAB", "CZ.DAB")))
    }

    @Test fun structuredLanguageHints() {
        assertTrue(probable("Scooby.Doo.S02E01.1080p.mkv", languages = listOf("cs")))
        assertTrue(probable("Scooby.Doo.S02E01.1080p.mkv", languages = listOf("en", "cze")))
        assertTrue(probable("Scooby.Doo.S02E01.1080p.mkv", languages = listOf("cs-CZ")))
        assertFalse(probable("Scooby.Doo.S02E01.1080p.mkv", languages = listOf("en", "sk")))
    }

    @Test fun subtitleOnlyMustNotLookLikeCzechAudio() {
        assertFalse(probable("Scooby.Doo.1080p.ENG.Audio.CZ.SUB.mkv"))
        assertFalse(probable("Scooby.Doo.1080p.CZ.titulky.mkv"))
        assertFalse(probable("Movie Czech subtitles only"))
        assertFalse(probable("Movie SUB CZ 1080p"))
        assertFalse(probable("Movie CZ.SUBS + SK.audio"))
        assertFalse(probable("Scooby.Doo 1080p CZ SUB", languages = listOf("cs")))
    }

    @Test fun titleAndUnrelatedMetadataCannotForceBadge() {
        assertFalse(probable("Scooby Doo 1080p EN"))
        assertFalse(probable("movie.S02E01.1080p.WEB-DL"))
        assertFalse(probable("Czechia.Travel.Series.2024"))
        assertFalse(probable("awesomeCZproject 1080p"))
        assertFalse(probable("SK dabing"))
        assertFalse(probable("CZ subtitles 1080p ENG"))
        assertFalse(probable(""))
    }

    @Test fun dubbingWinsOverSubtitleEvidence() {
        assertTrue(probable("ENG audio, CZ SUB, Czech dubbing"))
        assertTrue(probable("film.1080p.CZ.DAB.CZ.SUB.mkv"))
    }

    @Test fun longAndMalformedNamesDoNotCauseProblems() {
        assertFalse(probable("x".repeat(3000) + " CZ"))
        assertFalse(probable("english audio", languages = listOf("", "unknown")))
    }
}
