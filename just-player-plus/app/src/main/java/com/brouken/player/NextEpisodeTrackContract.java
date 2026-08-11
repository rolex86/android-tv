package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;

import java.text.Normalizer;
import java.util.Collections;
import java.util.Locale;
import java.util.MissingResourceException;

/** Hard A/V-language contract carried between episodes in one JustPlayer Plus session. */
final class NextEpisodeTrackContract {
    @Nullable final String audioLanguage;
    @Nullable final String audioLabel;
    @Nullable final String audioMime;
    final int audioChannels;
    final boolean subtitlesDisabled;
    @Nullable final String subtitleLanguage;
    @Nullable final String subtitleLabel;
    @Nullable final String subtitleMime;
    final boolean subtitleForced;

    private NextEpisodeTrackContract(RememberedTrackStore.Selection selection) {
        audioLanguage = emptyToNull(selection.audioLanguage);
        audioLabel = emptyToNull(selection.audioLabel);
        audioMime = emptyToNull(selection.audioMime);
        audioChannels = selection.audioChannels;
        subtitlesDisabled = selection.subtitleDisabled;
        subtitleLanguage = emptyToNull(selection.subtitleLanguage);
        subtitleLabel = emptyToNull(selection.subtitleLabel);
        subtitleMime = emptyToNull(selection.subtitleMime);
        subtitleForced = selection.subtitleForced;
    }

    static NextEpisodeTrackContract fromSelection(
            RememberedTrackStore.Selection selection) {
        return new NextEpisodeTrackContract(selection);
    }

    boolean hasVerifiableAudioIdentity() {
        return audioLanguage != null || audioLabel != null;
    }

    @Nullable
    String requiredAudioLanguage() {
        return audioLanguage;
    }

    @Nullable
    TrackSelectionOverride findAudioOverride(Tracks tracks) {
        Candidate best = findBest(
                tracks,
                C.TRACK_TYPE_AUDIO,
                audioLanguage,
                audioLabel,
                audioMime,
                false,
                audioChannels);
        return best == null ? null : best.override();
    }

    @Nullable
    TrackSelectionOverride findSubtitleOverride(Tracks tracks) {
        if (subtitlesDisabled) {
            return null;
        }
        Candidate best = findBest(
                tracks,
                C.TRACK_TYPE_TEXT,
                subtitleLanguage,
                subtitleLabel,
                subtitleMime,
                subtitleForced,
                Format.NO_VALUE);
        return best == null ? null : best.override();
    }

    private static Candidate findBest(
            Tracks tracks,
            int trackType,
            @Nullable String requiredLanguage,
            @Nullable String requiredLabel,
            @Nullable String requiredMime,
            boolean requireForced,
            int requiredChannels) {
        if (requiredLanguage == null && requiredLabel == null) {
            return null;
        }
        Candidate best = null;
        int order = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int index = 0; index < trackGroup.length; index++) {
                Format format = trackGroup.getFormat(index);
                if (!group.isTrackSupported(index)
                        || !matchesIdentity(requiredLanguage, requiredLabel, format)
                        || requireForced && !isForced(format)) {
                    order++;
                    continue;
                }
                long score = order++;
                if (requiredLabel != null
                        && !normalizeLabel(requiredLabel).equals(normalizeLabel(format.label))) {
                    score += 10_000L;
                }
                if (requiredMime != null && !requiredMime.equals(format.sampleMimeType)) {
                    score += 1_000L;
                }
                if (requiredChannels > 0 && format.channelCount > 0) {
                    score += Math.abs(requiredChannels - format.channelCount) * 100L;
                }
                Candidate candidate = new Candidate(trackGroup, index, score);
                if (best == null || candidate.score < best.score) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    static boolean matchesIdentity(
            @Nullable String requiredLanguage,
            @Nullable String requiredLabel,
            Format candidate) {
        return matchesIdentity(
                requiredLanguage,
                requiredLabel,
                candidate.language,
                candidate.label);
    }

    static boolean matchesIdentity(
            @Nullable String requiredLanguage,
            @Nullable String requiredLabel,
            @Nullable String candidateLanguage,
            @Nullable String candidateLabel) {
        String required = normalizeLanguage(requiredLanguage);
        String actual = normalizeLanguage(candidateLanguage);
        if (!required.isEmpty()) {
            if (!actual.isEmpty()) {
                return required.equals(actual);
            }
            return labelIdentifiesLanguage(candidateLabel, required);
        }
        String requiredSemanticLabel = normalizeLabel(requiredLabel);
        return !requiredSemanticLabel.isEmpty()
                && requiredSemanticLabel.equals(normalizeLabel(candidateLabel));
    }

    private static boolean labelIdentifiesLanguage(
            @Nullable String label, String normalizedLanguage) {
        String normalizedLabel = " " + normalizeLabel(label) + " ";
        if (normalizedLabel.trim().isEmpty()) {
            return false;
        }
        for (Locale locale : Locale.getAvailableLocales()) {
            String candidateLanguage;
            try {
                candidateLanguage = locale.getISO3Language().toLowerCase(Locale.ROOT);
            } catch (MissingResourceException ignored) {
                continue;
            }
            if (!normalizedLanguage.equals(candidateLanguage)) {
                continue;
            }
            if (containsToken(normalizedLabel, locale.getLanguage())
                    || containsPhrase(normalizedLabel,
                    locale.getDisplayLanguage(Locale.ENGLISH))
                    || containsPhrase(normalizedLabel, locale.getDisplayLanguage(locale))) {
                return true;
            }
        }
        return containsToken(normalizedLabel, normalizedLanguage);
    }

    private static boolean isForced(Format format) {
        return (format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0
                || containsPhrase(" " + normalizeLabel(format.label) + " ", "forced")
                || containsPhrase(" " + normalizeLabel(format.label) + " ", "vynucene");
    }

    private static boolean containsToken(String label, String token) {
        String normalized = normalizeLabel(token);
        return !normalized.isEmpty() && label.contains(" " + normalized + " ");
    }

    private static boolean containsPhrase(String label, String phrase) {
        String normalized = normalizeLabel(phrase);
        return !normalized.isEmpty() && label.contains(" " + normalized + " ");
    }

    static String normalizeLanguage(@Nullable String language) {
        if (language == null || language.isEmpty() || "und".equalsIgnoreCase(language)) {
            return "";
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = normalized.indexOf('-');
        String base = separator < 0 ? normalized : normalized.substring(0, separator);
        String alias = canonicalLanguageAlias(base);
        if (alias != null) {
            return alias;
        }
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale.getLanguage().isEmpty()) {
            locale = new Locale(language);
        }
        try {
            String iso3 = locale.getISO3Language().toLowerCase(Locale.ROOT);
            alias = canonicalLanguageAlias(iso3);
            return alias == null ? iso3 : alias;
        } catch (MissingResourceException ignored) {
            return locale.getLanguage().toLowerCase(Locale.ROOT);
        }
    }

    @Nullable
    private static String canonicalLanguageAlias(String language) {
        switch (language) {
            case "cs":
            case "cz":
            case "cze":
            case "ces":
            case "czech":
                return "ces";
            case "sk":
            case "svk":
            case "slo":
            case "slk":
            case "slovak":
                return "slk";
            case "de":
            case "ger":
            case "deu":
                return "deu";
            case "fr":
            case "fre":
            case "fra":
                return "fra";
            case "nl":
            case "dut":
            case "nld":
                return "nld";
            case "zh":
            case "chi":
            case "zho":
                return "zho";
            default:
                return null;
        }
    }

    private static String normalizeLabel(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static final class Candidate {
        final TrackGroup group;
        final int index;
        final long score;

        Candidate(TrackGroup group, int index, long score) {
            this.group = group;
            this.index = index;
            this.score = score;
        }

        TrackSelectionOverride override() {
            return new TrackSelectionOverride(group, Collections.singletonList(index));
        }
    }
}
