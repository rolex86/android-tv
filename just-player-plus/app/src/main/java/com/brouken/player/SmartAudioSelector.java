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

/** Filters and ranks audio tracks without changing audio rendering or passthrough. */
final class SmartAudioSelector {
    private SmartAudioSelector() {}

    @Nullable
    static TrackSelectionOverride findPreferredAudio(
            Tracks tracks,
            String[] preferredLanguages,
            boolean ignoreCommentary,
            boolean ignoreAudioDescription,
            boolean preferBestQuality,
            String contentPreference,
            boolean allowMediaDefault) {
        Candidate best = findBest(
                tracks, preferredLanguages, ignoreCommentary,
                ignoreAudioDescription, preferBestQuality, contentPreference,
                allowMediaDefault);
        if (best == null) {
            // Never leave the user without audio only because all labels are unusual.
            best = findBest(
                    tracks, preferredLanguages, false, false,
                    preferBestQuality, contentPreference, allowMediaDefault);
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
            boolean ignoreAudioDescription,
            boolean preferBestQuality,
            String contentPreference,
            boolean allowMediaDefault) {
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

                int languageRank = languageRank(
                        format, preferredLanguages, allowMediaDefault);
                if (languageRank < 0) {
                    languageRank = preferredLanguages.length + 100;
                }
                Candidate candidate = new Candidate(
                        trackGroup, trackIndex, format, languageRank,
                        contentRank(format, contentPreference),
                        sourceOrder++, preferBestQuality);
                if (best == null || candidate.score < best.score) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static int contentRank(Format format, String preference) {
        if (preference == null || "language".equals(preference)) {
            return 0;
        }
        boolean dubbed = isDubbed(format);
        boolean original = isExplicitOriginal(format);
        if ("original".equals(preference)) {
            if (original) {
                return 0;
            }
            return dubbed ? 2 : 1;
        }
        if ("dubbed".equals(preference)) {
            if (dubbed) {
                return 0;
            }
            return original ? 2 : 1;
        }
        return 0;
    }

    static boolean isDubbed(Format format) {
        return isDubbed(format.roleFlags, format.label);
    }

    static boolean isDubbed(int roleFlags, @Nullable String formatLabel) {
        if ((roleFlags & C.ROLE_FLAG_DUB) != 0) {
            return true;
        }
        String label = normalizeLabel(formatLabel);
        return containsToken(label, "dub")
                || contains(label, "dubbed")
                || contains(label, "dubbing")
                || contains(label, "cz dub")
                || contains(label, "czech dub")
                || contains(label, "dabing")
                || contains(label, "dabovany")
                || contains(label, "cesky dabing")
                || contains(label, "synchronized")
                || contains(label, "synchronised");
    }

    private static boolean isExplicitOriginal(Format format) {
        String label = normalizeLabel(format.label);
        return label.equals("ov")
                || contains(label, "original")
                || contains(label, "original audio")
                || contains(label, "original language")
                || contains(label, "original version")
                || contains(label, "puvodni zneni")
                || contains(label, "puvodni audio");
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

    static int languageRank(Format format,
                            String[] preferredLanguages,
                            boolean allowMediaDefault) {
        return languageRank(
                format.language,
                (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0,
                preferredLanguages,
                allowMediaDefault);
    }

    static int languageRank(@Nullable String language,
                            boolean mediaDefault,
                            String[] preferredLanguages,
                            boolean allowMediaDefault) {
        if (language == null || language.isEmpty() || "und".equalsIgnoreCase(language)) {
            if (allowMediaDefault && mediaDefault) {
                return preferredLanguages.length;
            }
            return -1;
        }
        String normalized = normalizeLanguage(language);
        for (int i = 0; i < preferredLanguages.length; i++) {
            if (normalized.equals(normalizeLanguage(preferredLanguages[i]))) {
                return i;
            }
        }
        if (allowMediaDefault && mediaDefault) {
            return preferredLanguages.length;
        }
        return -1;
    }

    private static int formatRank(@Nullable String mimeType) {
        if (mimeType == null) {
            return 50;
        }
        switch (mimeType) {
            case "audio/true-hd":
                return 0;
            case "audio/vnd.dts.hd":
                return 1;
            case "audio/eac3-joc":
                return 2;
            case "audio/eac3":
                return 3;
            case "audio/vnd.dts":
                return 4;
            case "audio/ac3":
                return 5;
            case "audio/flac":
                return 6;
            case "audio/opus":
                return 7;
            case "audio/mp4a-latm":
                return 8;
            default:
                return 20;
        }
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

    private static boolean containsToken(String label, String token) {
        String normalizedToken = normalizeLabel(token);
        return !normalizedToken.isEmpty()
                && (" " + label + " ").contains(" " + normalizedToken + " ");
    }

    private static final class Candidate {
        final TrackGroup trackGroup;
        final int trackIndex;
        final long score;

        Candidate(TrackGroup trackGroup,
                  int trackIndex,
                  Format format,
                  int languageRank,
                  int contentRank,
                  int sourceOrder,
                  boolean preferBestQuality) {
            this.trackGroup = trackGroup;
            this.trackIndex = trackIndex;

            long contentScore = (long) contentRank * 10_000_000_000_000L;
            long languageScore = (long) languageRank * 1_000_000_000L;
            if (!preferBestQuality) {
                this.score = contentScore + languageScore + sourceOrder;
                return;
            }

            int channels = Math.max(0, format.channelCount);
            int bitrate = Math.max(0, format.bitrate);
            long qualityScore = (long) formatRank(format.sampleMimeType) * 10_000_000L
                    + (long) (32 - Math.min(32, channels)) * 100_000L
                    + (100_000L - Math.min(100_000L, bitrate / 100));
            this.score = contentScore + languageScore + qualityScore + sourceOrder;
        }
    }
}
