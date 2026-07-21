package com.brouken.player.aisubtitles;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelectedSubtitleResolverTest {
    @Test
    public void matchesExternalConfigurationWhenMedia3ReplacesTrackId() {
        assertTrue(SelectedSubtitleResolver.matchesExternalConfiguration(
                "plus-external:opensubtitles-v3:abc",
                "OpenSubtitles v3 · ENG · Movie.Release",
                "eng",
                "1:2",
                "OpenSubtitles v3 · ENG · Movie.Release",
                "eng"));
    }

    @Test
    public void exactStableIdWinsWhenLabelsAreDuplicated() {
        assertTrue(SelectedSubtitleResolver.matchesExternalConfiguration(
                "plus-external:opensubtitles-v3:second",
                "Duplicate",
                "eng",
                "plus-external:opensubtitles-v3:second",
                "Duplicate",
                "eng"));
        assertFalse(SelectedSubtitleResolver.matchesExternalConfiguration(
                "plus-external:opensubtitles-v3:first",
                "Duplicate",
                "eng",
                "plus-external:opensubtitles-v3:second",
                "Duplicate",
                "eng"));
    }

    @Test
    public void doesNotGuessExternalTrackFromLanguageOnly() {
        assertFalse(SelectedSubtitleResolver.matchesExternalConfiguration(
                "plus-external:opensubtitles-v3:abc",
                "OpenSubtitles v3 · ENG · Movie.Release",
                "eng",
                "embedded:4",
                "English",
                "eng"));
    }
}
