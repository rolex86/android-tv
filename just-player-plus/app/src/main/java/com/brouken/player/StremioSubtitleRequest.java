package com.brouken.player;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses the identity hints Stremio sends to subtitle add-ons immediately before playback. */
final class StremioSubtitleRequest {
    final String type;
    final String videoId;
    @Nullable final String filename;

    private StremioSubtitleRequest(
            String type, String videoId, @Nullable String filename) {
        this.type = type;
        this.videoId = videoId;
        this.filename = filename;
    }

    @Nullable
    static StremioSubtitleRequest parse(@Nullable String requestTarget) {
        if (requestTarget == null || requestTarget.length() > 4_096) {
            return null;
        }
        int querySeparator = requestTarget.indexOf('?');
        String path = querySeparator < 0
                ? requestTarget : requestTarget.substring(0, querySeparator);
        String query = querySeparator < 0
                ? "" : requestTarget.substring(querySeparator + 1);
        if (!path.endsWith(".json")) {
            return null;
        }

        String type;
        String prefix;
        if (path.startsWith("/subtitles/movie/")) {
            type = "movie";
            prefix = "/subtitles/movie/";
        } else if (path.startsWith("/subtitles/series/")) {
            type = "series";
            prefix = "/subtitles/series/";
        } else {
            return null;
        }

        String resource = path.substring(
                prefix.length(), path.length() - ".json".length());
        int extrasSeparator = resource.indexOf('/');
        String pathExtras = extrasSeparator < 0
                ? "" : resource.substring(extrasSeparator + 1);
        Map<String, String> extras = new LinkedHashMap<>();
        decodeExtras(pathExtras, extras);
        decodeExtras(query, extras);
        String videoId = firstNonEmpty(
                extras.get("videoID"), extras.get("videoId"), extras.get("video_id"));
        if (videoId == null
                || StremioConnectorStore.Content.fromValues(type, videoId) == null) {
            return null;
        }
        String filename = firstNonEmpty(extras.get("filename"), extras.get("fileName"));
        return new StremioSubtitleRequest(type, videoId, filename);
    }

    private static void decodeExtras(String encoded, Map<String, String> target) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String part : encoded.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0 || separator == part.length() - 1) {
                continue;
            }
            try {
                String key = URLDecoder.decode(
                        part.substring(0, separator), StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(
                        part.substring(separator + 1), StandardCharsets.UTF_8.name());
                if (!key.isEmpty() && !value.isEmpty() && !target.containsKey(key)) {
                    target.put(key, value);
                }
            } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
                // Ignore malformed advisory metadata and keep playback independent.
            }
        }
    }

    @Nullable
    private static String firstNonEmpty(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
