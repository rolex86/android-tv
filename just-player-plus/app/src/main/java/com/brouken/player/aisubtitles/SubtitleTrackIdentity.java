package com.brouken.player.aisubtitles;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Normalizes subtitle IDs and keeps runtime OpenSubtitles match confidence. */
public final class SubtitleTrackIdentity {
    private static final String EXTERNAL_PREFIX = "plus-external:";
    private static final String OPEN_SUBTITLES_V3_PREFIX =
            "plus-external:opensubtitles-v3:";
    private static final String OPEN_SUBTITLES_EXACT_PREFIX =
            OPEN_SUBTITLES_V3_PREFIX + "exact:";
    private static final String OPEN_SUBTITLES_LIKELY_PREFIX =
            OPEN_SUBTITLES_V3_PREFIX + "likely:";
    private static final String OPEN_SUBTITLES_UNKNOWN_PREFIX =
            OPEN_SUBTITLES_V3_PREFIX + "unknown:";
    private static final String AI_PREFIX = "plus-ai:";

    private static final Map<String, Integer> OPEN_SUBTITLES_RANKS =
            new ConcurrentHashMap<>();
    private static final Map<String, Integer> OPEN_SUBTITLES_SIGNATURE_RANKS =
            new ConcurrentHashMap<>();
    private static final Map<String, Integer> EMBEDDED_KIND_RANKS =
            new ConcurrentHashMap<>();

    private SubtitleTrackIdentity() {
    }

    public static String canonicalId(@Nullable String id) {
        if (id == null) {
            return "";
        }
        String canonical = id;
        while (true) {
            int separator = canonical.indexOf(':');
            if (separator <= 0) {
                return canonical;
            }
            boolean numericPrefix = true;
            for (int index = 0; index < separator; index++) {
                if (!Character.isDigit(canonical.charAt(index))) {
                    numericPrefix = false;
                    break;
                }
            }
            if (!numericPrefix) {
                return canonical;
            }
            canonical = canonical.substring(separator + 1);
        }
    }

    public static boolean sameStableId(@Nullable String first, @Nullable String second) {
        String canonicalFirst = canonicalId(first);
        String canonicalSecond = canonicalId(second);
        return !canonicalFirst.isEmpty() && Objects.equals(canonicalFirst, canonicalSecond);
    }

    public static boolean isExternal(@Nullable String id) {
        return canonicalId(id).startsWith(EXTERNAL_PREFIX);
    }

    public static boolean isOpenSubtitlesV3(@Nullable String id) {
        return canonicalId(id).startsWith(OPEN_SUBTITLES_V3_PREFIX);
    }

    public static boolean isOpenSubtitlesV3(@Nullable String id, @Nullable String label) {
        return isOpenSubtitlesV3(id) || isOpenSubtitlesLabel(label);
    }

    public static boolean isAi(@Nullable String id) {
        return canonicalId(id).startsWith(AI_PREFIX);
    }

    public static void resetOpenSubtitlesMatches() {
        OPEN_SUBTITLES_RANKS.clear();
        OPEN_SUBTITLES_SIGNATURE_RANKS.clear();
        EMBEDDED_KIND_RANKS.clear();
    }

    public static void registerOpenSubtitlesMatch(@Nullable String id,
                                                  @Nullable String language,
                                                  int selectionFlags,
                                                  int roleFlags,
                                                  @Nullable String label,
                                                  int rank) {
        String canonical = canonicalId(id);
        if (!canonical.isEmpty()) {
            registerBestRank(OPEN_SUBTITLES_RANKS, canonical, rank);
        }
        String signature = openSubtitlesSignature(
                language, selectionFlags, roleFlags, label);
        if (!signature.isEmpty()) {
            registerBestRank(OPEN_SUBTITLES_SIGNATURE_RANKS, signature, rank);
        }
        String kind = subtitleKindKey(language, selectionFlags, roleFlags, label);
        if (rank == 0 && !kind.isEmpty()) {
            // Generic and filename-only matches identify an external subtitle, but are not
            // strong enough to stand in for a selected embedded track. Only a movie-hash
            // result may expose an online replacement for embedded subtitle translation.
            registerBestRank(EMBEDDED_KIND_RANKS, kind, rank);
        }
    }

    private static void registerBestRank(Map<String, Integer> ranks,
                                         String key,
                                         int rank) {
        synchronized (ranks) {
            Integer previous = ranks.get(key);
            if (previous == null || rank < previous) {
                ranks.put(key, rank);
            }
        }
    }

    /** 0 = exact movie-hash match, 1 = likely release match, 2 = unverified. */
    public static int openSubtitlesMatchRank(@Nullable String id) {
        String canonical = canonicalId(id);
        Integer registered = OPEN_SUBTITLES_RANKS.get(canonical);
        if (registered != null) {
            return registered;
        }
        // Backward compatibility with the first experimental branch build.
        if (canonical.startsWith(OPEN_SUBTITLES_EXACT_PREFIX)) {
            return 0;
        }
        if (canonical.startsWith(OPEN_SUBTITLES_LIKELY_PREFIX)) {
            return 1;
        }
        if (canonical.startsWith(OPEN_SUBTITLES_UNKNOWN_PREFIX)
                || canonical.startsWith(OPEN_SUBTITLES_V3_PREFIX)) {
            return 2;
        }
        return Integer.MAX_VALUE;
    }

    public static int openSubtitlesMatchRank(@Nullable String id,
                                             @Nullable String language,
                                             int selectionFlags,
                                             int roleFlags,
                                             @Nullable String label) {
        int idRank = openSubtitlesMatchRank(id);
        if (idRank != Integer.MAX_VALUE) {
            return idRank;
        }
        Integer signatureRank = OPEN_SUBTITLES_SIGNATURE_RANKS.get(
                openSubtitlesSignature(language, selectionFlags, roleFlags, label));
        return signatureRank == null ? Integer.MAX_VALUE : signatureRank;
    }

    public static int embeddedMatchRank(@Nullable String language,
                                        int selectionFlags,
                                        int roleFlags,
                                        @Nullable String label) {
        Integer rank = EMBEDDED_KIND_RANKS.get(
                subtitleKindKey(language, selectionFlags, roleFlags, label));
        if (rank == null) {
            return Integer.MAX_VALUE;
        }
        // A movie-hash match verifies the external subtitle against the video, not the bytes of an
        // embedded track. Matching language/kind is therefore shown as a likely proxy.
        return rank == 0 ? 1 : Integer.MAX_VALUE;
    }

    public static String matchIcon(@Nullable String id) {
        return iconForRank(openSubtitlesMatchRank(id));
    }

    public static String matchIcon(@Nullable String id,
                                   @Nullable String language,
                                   int selectionFlags,
                                   int roleFlags,
                                   @Nullable String label) {
        return iconForRank(openSubtitlesMatchRank(
                id, language, selectionFlags, roleFlags, label));
    }

    public static String embeddedMatchIcon(@Nullable String language,
                                           int selectionFlags,
                                           int roleFlags,
                                           @Nullable String label) {
        return iconForRank(embeddedMatchRank(language, selectionFlags, roleFlags, label));
    }

    private static String iconForRank(int rank) {
        if (rank == 0) {
            return "✓";
        }
        if (rank == 1) {
            return "≈";
        }
        if (rank == 2) {
            return "?";
        }
        return "";
    }

    private static String subtitleKindKey(@Nullable String language,
                                          int selectionFlags,
                                          int roleFlags,
                                          @Nullable String label) {
        String normalizedLanguage = normalizeLanguage(language);
        if (normalizedLanguage.isEmpty() || "und".equals(normalizedLanguage)) {
            normalizedLanguage = inferLanguage(label);
        }
        if (normalizedLanguage.isEmpty()) {
            return "";
        }
        boolean forced = (selectionFlags & C.SELECTION_FLAG_FORCED) != 0
                || contains(label, "forced")
                || contains(label, "foreign parts")
                || contains(label, "signs and songs");
        boolean sdh = (roleFlags & C.ROLE_FLAG_CAPTION) != 0
                || contains(label, "sdh")
                || contains(label, "hearing impaired")
                || contains(label, "hard of hearing");
        return normalizedLanguage + '|' + (forced ? 'F' : sdh ? 'S' : 'N');
    }

    private static String openSubtitlesSignature(@Nullable String language,
                                                 int selectionFlags,
                                                 int roleFlags,
                                                 @Nullable String label) {
        if (!isOpenSubtitlesLabel(label)) {
            return "";
        }
        String kind = subtitleKindKey(language, selectionFlags, roleFlags, label);
        String normalizedLabel = normalizeText(label);
        return kind.isEmpty() || normalizedLabel.isEmpty()
                ? "" : kind + '|' + normalizedLabel;
    }

    private static boolean isOpenSubtitlesLabel(@Nullable String label) {
        return normalizeText(label).startsWith("opensubtitles v3");
    }

    private static String inferLanguage(@Nullable String label) {
        String normalized = normalizeText(label);
        if (hasToken(normalized, "english") || hasToken(normalized, "eng")) return "eng";
        if (hasToken(normalized, "czech") || hasToken(normalized, "ces")
                || hasToken(normalized, "cze") || hasToken(normalized, "cesky")) return "ces";
        if (hasToken(normalized, "slovak") || hasToken(normalized, "slk")
                || hasToken(normalized, "slo") || hasToken(normalized, "slovensky")) return "slk";
        if (hasToken(normalized, "german") || hasToken(normalized, "deu")
                || hasToken(normalized, "ger") || hasToken(normalized, "deutsch")) return "deu";
        return "";
    }

    private static String normalizeLanguage(@Nullable String language) {
        if (language == null) return "";
        String value = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = value.indexOf('-');
        if (dash > 0) value = value.substring(0, dash);
        switch (value) {
            case "cs": case "cze": case "ces": return "ces";
            case "sk": case "slo": case "slk": return "slk";
            case "en": case "eng": return "eng";
            case "de": case "ger": case "deu": return "deu";
            case "fr": case "fre": case "fra": return "fra";
            case "es": case "spa": return "spa";
            case "it": case "ita": return "ita";
            case "pl": case "pol": return "pol";
            case "pt": case "por": return "por";
            case "hu": case "hun": return "hun";
            case "ru": case "rus": return "rus";
            case "uk": case "ukr": return "ukr";
            default: return value.matches("[a-z]{2,3}") ? value : "";
        }
    }

    private static boolean contains(@Nullable String value, String marker) {
        return normalizeText(value).contains(marker);
    }

    private static boolean hasToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private static String normalizeText(@Nullable String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
