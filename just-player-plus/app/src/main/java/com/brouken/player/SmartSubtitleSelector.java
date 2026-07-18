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

/** Selects subtitle tracks without changing any audio or video renderer configuration. */
final class SmartSubtitleSelector {
    private SmartSubtitleSelector() {}

    @Nullable
    static TrackSelectionOverride findFullSubtitle(
            Tracks tracks,
            String[] preferredLanguages,
            boolean allowMediaDefault,
            boolean allowUnknownLanguage) {
        TrackSelectionOverride regular = findBest(
                tracks, preferredLanguages, allowMediaDefault, allowUnknownLanguage,
                false, true);
        if (regular != null) {
            return regular;
        }
        return findBest(
                tracks, preferredLanguages, allowMediaDefault, allowUnknownLanguage,
                true, false);
    }

    @Nullable
    static TrackSelectionOverride findForcedSubtitle(
            Tracks tracks,
            String[] preferredLanguages,
            boolean allowMediaDefault,
            boolean allowUnknownLanguage) {
        return findBest(
                tracks, preferredLanguages, allowMediaDefault, allowUnknownLanguage,
                true, false);
    }

    static boolean isAudioLanguageNative(@Nullable Format audioFormat, String[] nativeLanguages) {
        if (audioFormat == null || isUndetermined(audioFormat.language)) {
            return false;
        }
        String normalizedAudio = normalizeLanguage(audioFormat.language);
        for (String nativeLanguage : nativeLanguages) {
            if (normalizedAudio.equals(normalizeLanguage(nativeLanguage))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static TrackSelectionOverride findBest(
            Tracks tracks,
            String[] preferredLanguages,
            boolean allowMediaDefault,
            boolean allowUnknownLanguage,
            boolean requireForced,
            boolean excludeForced) {
        Candidate best = null;

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                if (!group.isTrackSupported(trackIndex)) {
                    continue;
                }
                Format format = trackGroup.getFormat(trackIndex);
                boolean forced = isForced(format);
                if (requireForced && !forced) {
                    continue;
                }
                if (excludeForced && forced) {
                    continue;
                }

                int languageRank = languageRank(format, preferredLanguages);
                if (languageRank < 0 && allowMediaDefault
                        && (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) {
                    languageRank = preferredLanguages.length + 50;
                }
                if (languageRank < 0) {
                    if (!allowUnknownLanguage || !hasUnknownLanguage(format)) {
                        continue;
                    }
                    languageRank = preferredLanguages.length + 100;
                }

                Candidate candidate = new Candidate(trackGroup, trackIndex, languageRank);
                if (best == null || candidate.score < best.score) {
                    best = candidate;
                }
            }
        }

        if (best == null) {
            return null;
        }
        return new TrackSelectionOverride(
                best.trackGroup, Collections.singletonList(best.trackIndex));
    }

    private static int languageRank(Format format, String[] preferredLanguages) {
        for (int i = 0; i < preferredLanguages.length; i++) {
            if (matchesLanguage(format, preferredLanguages[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matchesLanguage(Format format, String preferredLanguage) {
        String preferred = normalizeLanguage(preferredLanguage);
        if (!isUndetermined(format.language)
                && preferred.equals(normalizeLanguage(format.language))) {
            return true;
        }

        String label = normalizeLabel(format.label);
        if (label.isEmpty()) {
            return false;
        }

        Locale locale = findLocale(preferred);
        if (containsLabelToken(label, preferred)) {
            return true;
        }
        if (locale != null) {
            if (containsLabelToken(label, locale.getLanguage())) {
                return true;
            }
            if (containsLabelPhrase(label, locale.getDisplayLanguage(Locale.ENGLISH))) {
                return true;
            }
            if (containsLabelPhrase(label, locale.getDisplayLanguage(locale))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Locale findLocale(String normalizedLanguage) {
        for (Locale locale : Locale.getAvailableLocales()) {
            try {
                if (normalizedLanguage.equals(locale.getISO3Language().toLowerCase(Locale.ROOT))) {
                    return locale;
                }
            } catch (MissingResourceException ignored) {
                // Skip synthetic locales without an ISO-3 code.
            }
        }
        return null;
    }

    private static boolean isForced(Format format) {
        if ((format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            return true;
        }
        String label = normalizeLabel(format.label);
        return containsLabelPhrase(label, "forced")
                || containsLabelPhrase(label, "vynucene")
                || containsLabelPhrase(label, "erzwungen");
    }

    private static boolean hasUnknownLanguage(Format format) {
        return isUndetermined(format.language) && normalizeLabel(format.label).isEmpty();
    }

    private static boolean isUndetermined(@Nullable String language) {
        return language == null || language.isEmpty() || "und".equalsIgnoreCase(language);
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String clean = language.replace('_', '-');
        Locale locale = Locale.forLanguageTag(clean);
        if (locale.getLanguage().isEmpty()) {
            locale = new Locale(language);
        }
        try {
            return locale.getISO3Language().toLowerCase(Locale.ROOT);
        } catch (MissingResourceException ignored) {
            return locale.getLanguage().toLowerCase(Locale.ROOT);
        }
    }

    private static String normalizeLabel(@Nullable String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.isEmpty() ? "" : " " + normalized + " ";
    }

    private static boolean containsLabelToken(String normalizedLabel, String token) {
        String normalizedToken = normalizeLabel(token).trim();
        return !normalizedToken.isEmpty()
                && normalizedLabel.contains(" " + normalizedToken + " ");
    }

    private static boolean containsLabelPhrase(String normalizedLabel, String phrase) {
        String normalizedPhrase = normalizeLabel(phrase).trim();
        return !normalizedPhrase.isEmpty()
                && normalizedLabel.contains(" " + normalizedPhrase + " ");
    }

    private static final class Candidate {
        final TrackGroup trackGroup;
        final int trackIndex;
        final int score;

        Candidate(TrackGroup trackGroup, int trackIndex, int score) {
            this.trackGroup = trackGroup;
            this.trackIndex = trackIndex;
            this.score = score;
        }
    }
}
