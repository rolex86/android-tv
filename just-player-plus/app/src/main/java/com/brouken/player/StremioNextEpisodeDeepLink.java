package com.brouken.player;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

/** Builds the Android TV Stremio deep link for one resolved next episode. */
final class StremioNextEpisodeDeepLink {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private StremioNextEpisodeDeepLink() {
    }

    @Nullable
    static String build(@Nullable StremioEpisodeId episode) {
        if (episode == null) {
            return null;
        }
        return "stremio:///detail/series/"
                + encodePathSegment(episode.metaId)
                + "/"
                + encodePathSegment(episode.raw)
                + "?autoPlay=true";
    }

    static boolean shouldLaunchAtNaturalEnd(
            boolean hasNextEpisode,
            boolean returnResult,
            boolean continuationDismissed) {
        return hasNextEpisode && returnResult && !continuationDismissed;
    }

    /** RFC 3986 path-segment encoding while preserving Stremio's conventional ':' separators. */
    private static String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte rawByte : bytes) {
            int current = rawByte & 0xff;
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-'
                    || current == '.'
                    || current == '_'
                    || current == '~'
                    || current == ':') {
                encoded.append((char) current);
            } else {
                encoded.append('%')
                        .append(HEX[(current >>> 4) & 0x0f])
                        .append(HEX[current & 0x0f]);
            }
        }
        return encoded.toString();
    }
}
