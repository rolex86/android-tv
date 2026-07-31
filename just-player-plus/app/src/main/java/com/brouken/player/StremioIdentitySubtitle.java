package com.brouken.player;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Carries Stremio content identity through its cached subtitle response into the external-player
 * launch. The marker is removed before real subtitle tracks are attached.
 */
final class StremioIdentitySubtitle {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = StremioConnectorService.PORT;
    private static final String PATH_PREFIX = "/identity/v1/";
    private static final String SUBTITLE_ID = "justplayer-plus-identity-v1";
    private static final String SUBTITLE_LANGUAGE = "zxx";
    private static final int MAX_URI_LENGTH = 4_096;

    private StremioIdentitySubtitle() {
    }

    static String responseJson(StremioSubtitleRequest request) {
        JSONObject subtitle = new JSONObject();
        JSONObject response = new JSONObject();
        try {
            subtitle.put("id", SUBTITLE_ID);
            subtitle.put("url", buildUrl(request.type, request.videoId, request.filename));
            // ISO 639-2 "zxx" means no linguistic content and prevents the empty marker from
            // becoming a preferred human-language subtitle in Stremio's internal player.
            subtitle.put("lang", SUBTITLE_LANGUAGE);
            response.put("subtitles", new org.json.JSONArray().put(subtitle));
            return response.toString();
        } catch (JSONException impossible) {
            return "{\"subtitles\":[]}";
        }
    }

    static String buildUrl(String type, String videoId, @Nullable String filename) {
        StringBuilder url = new StringBuilder("http://")
                .append(HOST).append(':').append(PORT)
                .append(PATH_PREFIX)
                .append(encode(type)).append('/')
                .append(encode(videoId)).append(".vtt");
        if (filename != null && !filename.trim().isEmpty()) {
            url.append("?filename=").append(encode(filename.trim()));
        }
        return url.toString();
    }

    @Nullable
    static Identity parse(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URI_LENGTH) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || !HOST.equals(uri.getHost())
                || uri.getPort() != PORT) {
            return null;
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith(PATH_PREFIX) || !rawPath.endsWith(".vtt")) {
            return null;
        }
        String resource = rawPath.substring(
                PATH_PREFIX.length(), rawPath.length() - ".vtt".length());
        int separator = resource.indexOf('/');
        if (separator <= 0 || separator == resource.length() - 1
                || resource.indexOf('/', separator + 1) >= 0) {
            return null;
        }
        String type = decode(resource.substring(0, separator));
        String videoId = decode(resource.substring(separator + 1));
        if (!OpenSubtitlesV3Client.isSupportedContent(type, videoId)) {
            return null;
        }
        String filename = queryValue(uri.getRawQuery(), "filename");
        return new Identity(type, videoId, filename);
    }

    static boolean isMarkerPath(@Nullable String path) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith(".vtt")) {
            return false;
        }
        return parse("http://" + HOST + ':' + PORT + path) != null;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            return value;
        }
    }

    @Nullable
    private static String decode(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name()).trim();
        } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
            return null;
        }
    }

    @Nullable
    private static String queryValue(@Nullable String query, String expectedKey) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0 || separator == part.length() - 1) {
                continue;
            }
            String key = decode(part.substring(0, separator));
            if (!expectedKey.equals(key)) {
                continue;
            }
            return decode(part.substring(separator + 1));
        }
        return null;
    }

    static final class Identity {
        final String type;
        final String videoId;
        @Nullable final String filename;

        Identity(String type, String videoId, @Nullable String filename) {
            this.type = type;
            this.videoId = videoId;
            this.filename = filename;
        }
    }
}
