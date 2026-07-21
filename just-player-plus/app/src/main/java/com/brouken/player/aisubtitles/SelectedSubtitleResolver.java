package com.brouken.player.aisubtitles;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Resolves the exact selected text-track index and maps its stable ID back to a source URI. */
public final class SelectedSubtitleResolver {
    public static final String EXTERNAL_ID_PREFIX = "plus-external:";
    public static final String AI_ID_PREFIX = "plus-ai:";

    public enum Issue {
        NONE_SELECTED,
        EMBEDDED,
        IMAGE_BASED,
        UNSUPPORTED_FORMAT,
        URI_UNREADABLE
    }

    public static final class Resolution {
        @Nullable public final AiSubtitleSource source;
        @Nullable public final Issue issue;

        private Resolution(@Nullable AiSubtitleSource source, @Nullable Issue issue) {
            this.source = source;
            this.issue = issue;
        }

        static Resolution ready(AiSubtitleSource source) {
            return new Resolution(source, null);
        }

        static Resolution failed(Issue issue) {
            return new Resolution(null, issue);
        }

        public boolean isReady() {
            return source != null;
        }
    }

    private SelectedSubtitleResolver() {
    }

    public static Resolution resolve(@Nullable Player player) {
        if (player == null || player.getCurrentMediaItem() == null) {
            return Resolution.failed(Issue.NONE_SELECTED);
        }
        List<MediaItem.SubtitleConfiguration> configurations = subtitleConfigurations(
                player.getCurrentMediaItem());
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int index = 0; index < trackGroup.length; index++) {
                if (!group.isTrackSelected(index)) {
                    continue;
                }
                Format format = trackGroup.getFormat(index);
                String id = format.id;
                if (id == null || !id.startsWith(EXTERNAL_ID_PREFIX)) {
                    return Resolution.failed(Issue.EMBEDDED);
                }
                MediaItem.SubtitleConfiguration configuration = findConfiguration(
                        configurations, id);
                if (configuration == null || !hasReadableScheme(configuration.uri)) {
                    return Resolution.failed(Issue.URI_UNREADABLE);
                }

                String trackMime = normalizeMime(format.sampleMimeType);
                String configuredMime = normalizeMime(configuration.mimeType);
                String mime = isSupportedMime(configuredMime) || isImageBased(configuredMime)
                        ? configuredMime : trackMime;
                if (isImageBased(mime)) {
                    return Resolution.failed(Issue.IMAGE_BASED);
                }
                if (!isSupportedMime(mime)) {
                    return Resolution.failed(Issue.UNSUPPORTED_FORMAT);
                }
                return Resolution.ready(new AiSubtitleSource(
                        id,
                        format.label,
                        format.language,
                        mime,
                        "application/x-subrip".equals(mime) ? "srt" : "vtt",
                        configuration.uri));
            }
        }
        return Resolution.failed(Issue.NONE_SELECTED);
    }

    public static boolean isSupportedMime(@Nullable String mimeType) {
        String mime = normalizeMime(mimeType);
        return "application/x-subrip".equals(mime)
                || "text/vtt".equals(mime)
                || "application/vtt".equals(mime);
    }

    static boolean hasReadableScheme(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http":
            case "https":
            case "content":
            case "file":
                return true;
            default:
                return false;
        }
    }

    private static boolean isImageBased(String mime) {
        return "application/pgs".equals(mime)
                || "application/dvbsubs".equals(mime)
                || "application/vobsub".equals(mime);
    }

    private static String normalizeMime(@Nullable String mimeType) {
        return mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static MediaItem.SubtitleConfiguration findConfiguration(
            List<MediaItem.SubtitleConfiguration> configurations, String id) {
        for (MediaItem.SubtitleConfiguration configuration : configurations) {
            if (id.equals(configuration.id)) {
                return configuration;
            }
        }
        return null;
    }

    static List<MediaItem.SubtitleConfiguration> subtitleConfigurations(MediaItem mediaItem) {
        if (mediaItem.localConfiguration == null) {
            return Collections.emptyList();
        }
        return mediaItem.localConfiguration.subtitleConfigurations;
    }
}
