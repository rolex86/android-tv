package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;

import com.brouken.player.aisubtitles.SubtitleTrackIdentity;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preserves OpenSubtitles metadata while Stremio forwards a subtitle URL to the external player.
 * The loopback URL is self-contained and can also redirect non-JustPlayer consumers to the
 * original, HTTPS-only Stremio subtitle file.
 */
final class StremioPreloadedSubtitle {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = StremioConnectorService.PORT;
    private static final String PATH_PREFIX = "/opensubtitles/v1/";
    private static final int MAX_URI_LENGTH = 4_096;
    private static final int MAX_LABEL_LENGTH = 180;

    private StremioPreloadedSubtitle() {
    }

    static String buildUrl(OpenSubtitlesV3Client.Candidate candidate) {
        String identifier = cleanIdentifier(candidate.identifier, candidate.sourceOrder);
        String mime = MimeTypes.TEXT_VTT.equals(candidate.mimeType) ? "vtt" : "srt";
        return new StringBuilder("http://")
                .append(HOST).append(':').append(PORT)
                .append(PATH_PREFIX)
                .append(encode(candidate.language)).append('/')
                .append(encode(identifier)).append(".srt")
                .append("?source=").append(encode(candidate.url))
                .append("&label=").append(encode(candidate.label))
                .append("&mime=").append(mime)
                .append("&role=").append(candidate.roleFlags)
                .append("&selection=").append(candidate.selectionFlags)
                .append("&confidence=").append(candidate.confidence.rank)
                .toString();
    }

    @Nullable
    static Parsed parseRequestTarget(@Nullable String requestTarget) {
        if (requestTarget == null) {
            return null;
        }
        return parse(requestTarget.startsWith("/")
                ? "http://" + HOST + ':' + PORT + requestTarget
                : requestTarget);
    }

    @Nullable
    static Parsed parse(@Nullable String value) {
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
        if (!isPath(rawPath)) {
            return null;
        }
        String resource = rawPath.substring(
                PATH_PREFIX.length(), rawPath.length() - ".srt".length());
        int separator = resource.indexOf('/');
        if (separator <= 0 || separator == resource.length() - 1
                || resource.indexOf('/', separator + 1) >= 0) {
            return null;
        }
        String language = OpenSubtitlesV3Client.normalizeLanguage(
                decode(resource.substring(0, separator)));
        String identifier = decode(resource.substring(separator + 1));
        if (language.isEmpty() || identifier == null
                || !identifier.matches("[A-Za-z0-9._-]{1,80}")) {
            return null;
        }
        Map<String, String> query = queryValues(uri.getRawQuery());
        String sourceUrl = query.get("source");
        if (!isSafeSource(sourceUrl)) {
            return null;
        }
        String label = query.get("label");
        if (label == null || label.isEmpty() || label.length() > MAX_LABEL_LENGTH
                || !label.toLowerCase(java.util.Locale.ROOT).startsWith("opensubtitles")) {
            return null;
        }
        String mimeType = "vtt".equals(query.get("mime"))
                ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP;
        int roleFlags = parseInt(query.get("role"), C.ROLE_FLAG_SUBTITLE)
                & (C.ROLE_FLAG_SUBTITLE | C.ROLE_FLAG_CAPTION);
        int selectionFlags = parseInt(query.get("selection"), 0)
                & C.SELECTION_FLAG_FORCED;
        int confidence = parseInt(
                query.get("confidence"), OpenSubtitlesV3Client.MatchConfidence.UNKNOWN.rank);
        confidence = confidence == OpenSubtitlesV3Client.MatchConfidence.LIKELY.rank
                ? confidence : OpenSubtitlesV3Client.MatchConfidence.UNKNOWN.rank;
        return new Parsed(
                sourceUrl,
                identifier,
                language,
                label,
                mimeType,
                roleFlags,
                selectionFlags,
                confidence);
    }

    static boolean isPath(@Nullable String path) {
        return path != null && path.startsWith(PATH_PREFIX) && path.endsWith(".srt");
    }

    private static boolean isSafeSource(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > 2_048) {
            return false;
        }
        try {
            URI source = new URI(value);
            String host = source.getHost();
            return "https".equalsIgnoreCase(source.getScheme())
                    && host != null
                    && ("strem.io".equalsIgnoreCase(host)
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".strem.io"))
                    && (source.getPort() == -1 || source.getPort() == 443)
                    && source.getUserInfo() == null
                    && source.getFragment() == null;
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String cleanIdentifier(@Nullable String value, int sourceOrder) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("[A-Za-z0-9._-]{1,80}")
                ? clean : "result-" + Math.max(0, sourceOrder);
    }

    private static int parseInt(@Nullable String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, String> queryValues(@Nullable String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0 || separator == part.length() - 1) {
                continue;
            }
            String key = decode(part.substring(0, separator));
            String value = decode(part.substring(separator + 1));
            if (key != null && value != null && !result.containsKey(key)) {
                result.put(key, value);
            }
        }
        return result;
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

    static final class Parsed {
        final String sourceUrl;
        final String identifier;
        final String language;
        final String label;
        final String mimeType;
        final int roleFlags;
        final int selectionFlags;
        final int confidence;

        Parsed(String sourceUrl,
               String identifier,
               String language,
               String label,
               String mimeType,
               int roleFlags,
               int selectionFlags,
               int confidence) {
            this.sourceUrl = sourceUrl;
            this.identifier = identifier;
            this.language = language;
            this.label = label;
            this.mimeType = mimeType;
            this.roleFlags = roleFlags;
            this.selectionFlags = selectionFlags;
            this.confidence = confidence;
        }

        MediaItem.SubtitleConfiguration toConfiguration(boolean selected) {
            String id = OpenSubtitlesV3Client.TRACK_ID_PREFIX
                    + "preloaded-" + identifier + '-'
                    + Integer.toHexString(sourceUrl.hashCode());
            int flags = selectionFlags | (selected ? C.SELECTION_FLAG_DEFAULT : 0);
            SubtitleTrackIdentity.registerOpenSubtitlesMatch(
                    id, language, flags, roleFlags, label, confidence);
            return new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sourceUrl))
                    .setId(id)
                    .setLanguage(language)
                    .setLabel(label)
                    .setMimeType(mimeType)
                    .setRoleFlags(roleFlags)
                    .setSelectionFlags(flags)
                    .build();
        }
    }
}
