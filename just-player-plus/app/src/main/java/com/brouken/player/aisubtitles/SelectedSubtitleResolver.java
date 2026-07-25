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

import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the selected text track to a readable source, including matched online replacements. */
public final class SelectedSubtitleResolver {
    public static final String EXTERNAL_ID_PREFIX = "plus-external:";
    public static final String AI_ID_PREFIX = "plus-ai:";
    private static final String EMBEDDED_PROXY_ID_PREFIX = "plus-embedded-proxy:";

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

        Resolution override = resolveTextOverride(
                player.getTrackSelectionParameters(), configurations);
        if (override != null) {
            return override;
        }

        boolean embeddedSeen = false;
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
                MediaItem.SubtitleConfiguration external =
                        findExternalConfiguration(configurations, format);
                if (external == null) {
                    embeddedSeen = true;
                    Resolution replacement = resolveEmbeddedReplacement(format, configurations);
                    if (replacement.isReady()) {
                        return replacement;
                    }
                    continue;
                }
                Resolution resolved = resolveConfiguration(
                        external,
                        external.id,
                        format.label != null ? format.label : external.label,
                        format.language != null ? format.language : external.language);
                if (resolved.isReady()) {
                    return resolved;
                }
                externalFailure = resolved.issue;
            }
        }

        if (externalFailure != null) {
            return Resolution.failed(externalFailure);
        }
        return embeddedSeen
                ? Resolution.failed(Issue.EMBEDDED)
                : Resolution.failed(Issue.NONE_SELECTED);
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
                MediaItem.SubtitleConfiguration external =
                        findExternalConfiguration(configurations, format);
                if (external == null) {
                    return resolveEmbeddedReplacement(format, configurations);
                }
                return resolveConfiguration(
                        external,
                        external.id,
                        format.label != null ? format.label : external.label,
                        format.language != null ? format.language : external.language);
            }
        }
        return null;
    }

    private static Resolution resolveEmbeddedReplacement(
            Format embedded,
            List<MediaItem.SubtitleConfiguration> configurations) {
        if (isCommentary(embedded)) {
            return Resolution.failed(Issue.EMBEDDED);
        }
        AiSubtitleSource best = null;
        long bestScore = Long.MAX_VALUE;
        int order = 0;
        for (MediaItem.SubtitleConfiguration configuration : configurations) {
            if (!SubtitleTrackIdentity.isOpenSubtitlesV3(configuration.id)) {
                order++;
                continue;
            }
            Resolution resolved = resolveConfiguration(
                    configuration, configuration.id, configuration.label, configuration.language);
            AiSubtitleSource candidate = resolved.source;
            if (candidate == null
                    || !sameKnownLanguage(embedded, candidate.language)
                    || !sameSubtitleKind(embedded, configuration)) {
                order++;
                continue;
            }
            long score = (long) SubtitleTrackIdentity.openSubtitlesMatchRank(
                    configuration.id) * 10_000L + order++;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            return Resolution.failed(Issue.EMBEDDED);
        }
        return Resolution.ready(new AiSubtitleSource(
                embeddedProxyId(embedded),
                embeddedDisplayName(embedded),
                embedded.language != null ? embedded.language : best.language,
                best.mimeType,
                best.sourceFormat,
                best.uri));
    }

    private static boolean sameKnownLanguage(Format embedded, @Nullable String candidateLanguage) {
        String embeddedLanguage = inferLanguage(embedded.language, embedded.label);
        String candidate = normalizeLanguage(candidateLanguage);
        return !embeddedLanguage.isEmpty()
                && !candidate.isEmpty()
                && Objects.equals(embeddedLanguage, candidate);
    }

    private static boolean sameSubtitleKind(
            Format embedded,
            MediaItem.SubtitleConfiguration candidate) {
        return isForced(embedded.selectionFlags, embedded.label)
                == isForced(candidate.selectionFlags, candidate.label)
                && isSdh(embedded.roleFlags, embedded.label)
                == isSdh(candidate.roleFlags, candidate.label);
    }

    private static String inferLanguage(@Nullable String language, @Nullable String label) {
        String normalized = normalizeLanguage(language);
        if (!normalized.isEmpty() && !"und".equals(normalized)) {
            return normalized;
        }
        String text = normalizeText(label);
        if (hasToken(text, "english") || hasToken(text, "eng")) return "eng";
        if (hasToken(text, "czech") || hasToken(text, "ces")
                || hasToken(text, "cze") || hasToken(text, "cesky")) return "ces";
        if (hasToken(text, "slovak") || hasToken(text, "slk")
                || hasToken(text, "slo") || hasToken(text, "slovensky")) return "slk";
        if (hasToken(text, "german") || hasToken(text, "deu")
                || hasToken(text, "ger") || hasToken(text, "deutsch")) return "deu";
        return "";
    }

    private static boolean hasToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private static String embeddedProxyId(Format format) {
        String canonical = SubtitleTrackIdentity.canonicalId(format.id);
        if (!canonical.isEmpty()) {
            return EMBEDDED_PROXY_ID_PREFIX + canonical;
        }
        String signature = normalizeText(format.label) + "|"
                + normalizeLanguage(format.language) + "|"
                + normalizeMime(format.sampleMimeType) + "|"
                + format.selectionFlags + "|" + format.roleFlags;
        return EMBEDDED_PROXY_ID_PREFIX + Integer.toHexString(signature.hashCode());
    }

    private static String embeddedDisplayName(Format format) {
        if (format.label != null && !format.label.trim().isEmpty()) {
            return format.label.trim();
        }
        if (format.language != null && !format.language.trim().isEmpty()) {
            return format.language.trim() + " · Embedded";
        }
        return "Embedded subtitles";
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

    private static boolean isForced(int selectionFlags, @Nullable String label) {
        return (selectionFlags & C.SELECTION_FLAG_FORCED) != 0
                || containsLabel(label, "forced")
                || containsLabel(label, "vynucene")
                || containsLabel(label, "foreign parts")
                || containsLabel(label, "signs and songs")
                || containsLabel(label, "signs songs");
    }

    private static boolean isSdh(int roleFlags, @Nullable String label) {
        return (roleFlags & C.ROLE_FLAG_CAPTION) != 0
                || containsLabel(label, "sdh")
                || containsLabel(label, "hearing impaired")
                || containsLabel(label, "hard of hearing");
    }

    private static boolean isCommentary(Format format) {
        return containsLabel(format.label, "commentary")
                || containsLabel(format.label, "komentar");
    }

    private static boolean containsLabel(@Nullable String label, String marker) {
        return normalizeText(label).contains(marker);
    }

    private static String normalizeMime(@Nullable String mimeType) {
        return mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static String normalizeLanguage(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String language = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = language.indexOf('-');
        language = separator > 0 ? language.substring(0, separator) : language;
        switch (language) {
            case "cs":
            case "cze":
            case "ces":
                return "ces";
            case "sk":
            case "slo":
            case "slk":
                return "slk";
            case "en":
            case "eng":
                return "eng";
            case "de":
            case "ger":
            case "deu":
                return "deu";
            default:
                return language;
        }
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
