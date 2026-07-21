package com.brouken.player.aisubtitles;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelectedSubtitleResolverTest {
    @Test
    public void normalizesMedia3MergedSourcePrefixes() {
        assertEquals(
                "plus-external:opensubtitles-v3:abc",
                SubtitleTrackIdentity.canonicalId(
                        "1:plus-external:opensubtitles-v3:abc"));
        assertEquals(
                "plus-ai:cache-key",
                SubtitleTrackIdentity.canonicalId("2:1:plus-ai:cache-key"));
    }

    @Test
    public void matchesActualMedia3PrefixedOpenSubtitlesId() {
        assertTrue(SelectedSubtitleResolver.matchesExternalConfiguration(
                "plus-external:opensubtitles-v3:abc",
                "OpenSubtitles v3 · ENG · Movie.Release",
                "eng",
                "1:plus-external:opensubtitles-v3:abc",
                "OpenSubtitles v3 · ENG · Movie.Release",
                "eng"));
    }

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
                "3:plus-external:opensubtitles-v3:second",
                "Duplicate",
                "eng"));
        assertFalse(SelectedSubtitleResolver.matchesExternalConfiguration(
                "plus-external:opensubtitles-v3:first",
                "Duplicate",
                "eng",
                "3:plus-external:opensubtitles-v3:second",
                "Duplicate",
                "eng"));
    }

    @Test
    public void identifiesPrefixedExternalAndAiTracks() {
        assertTrue(SubtitleTrackIdentity.isExternal(
                "1:plus-external:opensubtitles-v3:abc"));
        assertTrue(SubtitleTrackIdentity.isOpenSubtitlesV3(
                "1:plus-external:opensubtitles-v3:abc"));
        assertTrue(SubtitleTrackIdentity.sameStableId(
                "plus-ai:cache-key", "2:plus-ai:cache-key"));
        assertFalse(SubtitleTrackIdentity.isExternal("1:embedded:english"));
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
