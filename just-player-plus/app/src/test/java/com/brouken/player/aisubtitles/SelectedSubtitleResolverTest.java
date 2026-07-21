package com.brouken.player.aisubtitles;

import android.net.Uri;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SelectedSubtitleResolverTest {
    @Test
    public void matchesExternalConfigurationWhenMedia3ReplacesTrackId() {
        MediaItem.SubtitleConfiguration configuration = external(
                "plus-external:opensubtitles-v3:abc",
                "OpenSubtitles v3 · ENG · Movie.Release",
                "eng");
        Format selectedFormat = new Format.Builder()
                .setId("1:2")
                .setLabel("OpenSubtitles v3 · ENG · Movie.Release")
                .setLanguage("eng")
                .setSampleMimeType("application/x-media3-cues")
                .build();

        MediaItem.SubtitleConfiguration match =
                SelectedSubtitleResolver.findExternalConfiguration(
                        Collections.singletonList(configuration), selectedFormat);

        assertEquals(configuration, match);
    }

    @Test
    public void exactStableIdWinsWhenLabelsAreDuplicated() {
        MediaItem.SubtitleConfiguration first = external(
                "plus-external:opensubtitles-v3:first", "Duplicate", "eng");
        MediaItem.SubtitleConfiguration second = external(
                "plus-external:opensubtitles-v3:second", "Duplicate", "eng");
        Format selectedFormat = new Format.Builder()
                .setId(second.id)
                .setLabel("Duplicate")
                .setLanguage("eng")
                .build();

        MediaItem.SubtitleConfiguration match =
                SelectedSubtitleResolver.findExternalConfiguration(
                        Arrays.asList(first, second), selectedFormat);

        assertEquals(second, match);
    }

    @Test
    public void doesNotGuessExternalTrackFromLanguageOnly() {
        MediaItem.SubtitleConfiguration configuration = external(
                "plus-external:opensubtitles-v3:abc",
                "OpenSubtitles v3 · ENG · Movie.Release",
                "eng");
        Format embeddedFormat = new Format.Builder()
                .setId("embedded:4")
                .setLabel("English")
                .setLanguage("eng")
                .build();

        assertNull(SelectedSubtitleResolver.findExternalConfiguration(
                Collections.singletonList(configuration), embeddedFormat));
    }

    private static MediaItem.SubtitleConfiguration external(
            String id, String label, String language) {
        return new MediaItem.SubtitleConfiguration.Builder(
                Uri.parse("https://example.test/" + id.hashCode() + ".srt"))
                .setId(id)
                .setLabel(label)
                .setLanguage(language)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .build();
    }
}
