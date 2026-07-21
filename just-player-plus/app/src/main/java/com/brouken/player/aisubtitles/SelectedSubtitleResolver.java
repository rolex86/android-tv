package com.brouken.player.aisubtitles;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the selected text track and maps it back to its external source URI. */
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

        Resolution overrideResolution = resolveTextOverride(
                player.getTrackSelectionParameters(), configurations);
        if (overrideResolution != null) {
            return overrideResolution;
        }

        boolean selectedEmbeddedTrackSeen = false;
        Issue externalFailure = null;
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
                MediaItem.SubtitleConfiguration configuration =
                        findExternalConfiguration(configurations, format);
                if (configuration == null) {
                    selectedEmbeddedTrackSeen = true;
                    continue;
                }
                Resolution resolution = resolveConfiguration(
                        configuration,
                        configuration.id,
                        format.label != null ? format.label : configuration.label,
                        format.language != null ? format.language : configuration.language);
                if (resolution.isReady()) {
                    return resolution;
                }
                externalFailure = resolution.issue;
            }
        }

        if (externalFailure != null) {
            return Resolution.failed(externalFailure);
        }
        if (selectedEmbeddedTrackSeen) {
            return Resolution.failed(Issue.EMBEDDED);
        }
        return Resolution.failed(Issue.NONE_SELECTED);
    }

    @Nullable
    private static Resolution resolveTextOverride(
            TrackSelectionParameters parameters,
            List<MediaItem.SubtitleConfiguration> configurations) {
        for (Map.Entry<TrackGroup, TrackSelectionOverride> entry
                : parameters.overrides.entrySet()) {
            TrackGroup trackGroup = entry.getKey();
            TrackSelectionOverride override = entry.getValue();
            if (override.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int index : override.trackIndices) {
                if (index < 0 || index >= trackGroup.length) {
                    continue;
                }
                Format format = trackGroup.getFormat(index);
                MediaItem.SubtitleConfiguration configuration =
                        findExternalConfiguration(configurations, format);
                if (configuration == null) {
                    return Resolution.failed(Issue.EMBEDDED);
                }
                return resolveConfiguration(
                        configuration,
                        configuration.id,
                        format.label != null ? format.label : configuration.label,
                        format.language != null ? format.language : configuration.language);
            }
        }
        return null;
    }

    @Nullable
    static MediaItem.SubtitleConfiguration findExternalConfiguration(
            List<MediaItem.SubtitleConfiguration> configurations,
            Format selectedFormat) {
        MediaItem.SubtitleConfiguration labelMatch = null;
        for (MediaItem.SubtitleConfiguration configuration : configurations) {
            if (!matchesExternalConfiguration(
                    configuration.id,
                    configuration.label,
                    configuration.language,
                    selectedFormat.id,
                    selectedFormat.label,
                    selectedFormat.language)) {
                continue;
            }
            if (SubtitleTrackIdentity.sameStableId(selectedFormat.id, configuration.id)) {
                return configuration;
            }
            if (labelMatch != null) {
                return null;
            }
            labelMatch = configuration;
        }
        return labelMatch;
    }

    static boolean matchesExternalConfiguration(
            @Nullable String configurationId,
            @Nullable String configurationLabel,
            @Nullable String configurationLanguage,
            @Nullable String selectedId,
            @Nullable String selectedLabel,
            @Nullable String selectedLanguage) {
        if (!SubtitleTrackIdentity.isExternal(configurationId)) {
            return false;
        }
        if (SubtitleTrackIdentity.sameStableId(selectedId, configurationId)) {
            return true;
        }
        if (SubtitleTrackIdentity.isExternal(selectedId)) {
            return false;
        }
        String normalizedSelectedLabel = normalizeText(selectedLabel);
        if (normalizedSelectedLabel.isEmpty()
                || !normalizedSelectedLabel.equals(normalizeText(configurationLabel))) {
            return false;
        }
        return languagesMatch(
                normalizeLanguage(selectedLanguage),
                normalizeLanguage(configurationLanguage));
    }

    private static Resolution resolveConfiguration(
            @Nullable MediaItem.SubtitleConfiguration configuration,
            @Nullable String id,
            @Nullable String label,
            @Nullable String language) {
        if (configuration == null || id == null || !hasReadableScheme(configuration.uri)) {
            return Resolution.failed(Issue.URI_UNREADABLE);
        }
        String mime = normalizeMime(configuration.mimeType);
        if (isImageBased(mime)) {
            return Resolution.failed(Issue.IMAGE_BASED);
        }
        if (!isSupportedMime(mime)) {
            return Resolution.failed(Issue.UNSUPPORTED_FORMAT);
        }
        return Resolution.ready(new AiSubtitleSource(
                SubtitleTrackIdentity.canonicalId(id),
                label,
                language,
                mime,
                "application/x-subrip".equals(mime) ? "srt" : "vtt",
                configuration.uri));
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

    private static String normalizeText(@Nullable String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeLanguage(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String language = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = language.indexOf('-');
        return separator > 0 ? language.substring(0, separator) : language;
    }

    private static boolean languagesMatch(String first, String second) {
        return first.isEmpty() || second.isEmpty() || Objects.equals(first, second);
    }

    static List<MediaItem.SubtitleConfiguration> subtitleConfigurations(MediaItem mediaItem) {
        if (mediaItem.localConfiguration == null) {
            return Collections.emptyList();
        }
        return mediaItem.localConfiguration.subtitleConfigurations;
    }
}
