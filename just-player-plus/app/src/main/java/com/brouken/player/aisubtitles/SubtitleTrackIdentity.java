package com.brouken.player.aisubtitles;

import androidx.annotation.Nullable;

import java.util.Objects;

/** Normalizes stable subtitle IDs after Media3 prefixes merged-source indices such as "1:". */
public final class SubtitleTrackIdentity {
    private static final String EXTERNAL_PREFIX = "plus-external:";
    private static final String OPEN_SUBTITLES_V3_PREFIX =
            "plus-external:opensubtitles-v3:";
    private static final String AI_PREFIX = "plus-ai:";

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

    public static boolean isAi(@Nullable String id) {
        return canonicalId(id).startsWith(AI_PREFIX);
    }
}
