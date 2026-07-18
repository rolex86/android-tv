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

/** Filters commentary and audio-description tracks without changing audio rendering. */
final class SmartAudioSelector {
    private SmartAudioSelector() {}

    @Nullable
    static TrackSelectionOverride findPreferredAudio(
            Tracks tracks,
            String[] preferredLanguages,
            boolean ignoreCommentary,
            boolean ignoreAudioDescription) {
        Candidate best = findBest(
                tracks, preferredLanguages, ignoreCommentary, ignoreAudioDescription);
        if (best == null) {
            // Never leave the user without audio only because all labels are unusual.
            best = findBest(tracks, preferredLanguages, false, false);
        }
        if (best == null) {
            return null;
        }
        return new TrackSelectionOverride(
                best.trackGroup, Collections.singletonList(best.trackIndex));
    }

    @Nullable
    private static Candidate findBest(
            Tracks tracks,
            String[] preferredLanguages,
            boolean ignoreCommentary,
            boolean ignoreAudioDescription) {
        Candidate best = null;
        int sourceOrder = 0;

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                if (!group.isTrackSupported(trackIndex)) {
                    sourceOrder++;
                    continue;
                }
                Format format = trackGroup.getFormat(trackIndex);
                if (ignoreCommentary && isCommentary(format)) {
                    sourceOrder++;
                    continue;
                }
                if (ignoreAudioDescription && isAudioDescription(format)) {
                    sourceOrder++;
                    continue;
                }

                int languageRank = languageRank(format.language, preferredLanguages);
                if (languageRank < 0) {
                    languageRank = preferredLanguages.length + 100;
                }
                Candidate candidate = new Candidate(
                        trackGroup, trackIndex, languageRank, sourceOrder++);
                if (best == null || candidate.score < best.score) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean isCommentary(Format format) {
        if ((format.roleFlags & C.ROLE_FLAG_COMMENTARY) != 0) {
            return true;
        }
        String label = normalizeLabel(format.label);
        return contains(label, "commentary")
                || contains(label, "director commentary")
                || contains(label, "cast commentary")
                || contains(label, "komentar")
                || contains(label, "komentář");
    }

    private static boolean isAudioDescription(Format format) {
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) {
            return true;
        }
        String label = normalizeLabel(format.label);
        return contains(label, "audio description")
                || contains(label, "descriptive audio")
                || contains(label, "visually impaired")
                || contains(label, "described video")
                || contains(label, "audiodescription")
                || contains(label, "audiodeskripce");
    }

    private static int languageRank(@Nullable String language, String[] preferredLanguages) {
        if (language == null || language.isEmpty() || "und".equalsIgnoreCase(language)) {
            return -1;
        }
        String normalized = normalizeLanguage(language);
        for (int i = 0; i < preferredLanguages.length; i++) {
            if (normalized.equals(normalizeLanguage(preferredLanguages[i]))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeLanguage(String language) {
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
        return Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static boolean contains(String label, String phrase) {
        String normalizedPhrase = normalizeLabel(phrase);
        return !normalizedPhrase.isEmpty() && label.contains(normalizedPhrase);
    }

    private static final class Candidate {
        final TrackGroup trackGroup;
        final int trackIndex;
        final long score;

        Candidate(TrackGroup trackGroup, int trackIndex, int languageRank, int sourceOrder) {
            this.trackGroup = trackGroup;
            this.trackIndex = trackIndex;
            this.score = ((long) languageRank * 1_000_000L) + sourceOrder;
        }
    }
}
