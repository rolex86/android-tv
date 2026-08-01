package com.brouken.player;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, validated journal of OpenSubtitles listings fetched by the local Connector.
 *
 * <p>Stremio does not consistently forward subtitle add-on results to external players. Keeping
 * the already resolved candidates here lets JustPlayer attach them to the first media item without
 * relying on Stremio's external-player intent or rebuilding active playback.</p>
 */
final class StremioSubtitlePreloadCache {
    static final long MAX_AGE_MS = 30L * 24L * 60L * 60_000L;
    private static final int MAX_ENTRIES = 48;
    private static final int MAX_TRACKS = 30;
    private static final int MAX_ENCODED_LENGTH = 512 * 1024;

    private StremioSubtitlePreloadCache() {
    }

    static String update(
            @Nullable String encoded,
            StremioSubtitleRequest request,
            String[] preferredLanguages,
            List<OpenSubtitlesV3Client.Candidate> candidates,
            long nowMs) {
        JSONArray previous = parseArray(encoded);
        JSONArray updated = new JSONArray();
        String filename = StremioConnectorStore.normalizeFilename(request.filename);
        String languages = languageKey(preferredLanguages);

        JSONArray tracks = new JSONArray();
        for (OpenSubtitlesV3Client.Candidate candidate : candidates) {
            if (tracks.length() >= MAX_TRACKS) {
                break;
            }
            String value = StremioPreloadedSubtitle.buildUrl(candidate);
            if (StremioPreloadedSubtitle.parse(value) != null) {
                tracks.put(value);
            }
        }

        if (tracks.length() > 0 && !languages.isEmpty()) {
            try {
                JSONObject current = new JSONObject();
                current.put("type", request.type);
                current.put("id", request.videoId);
                current.put("timestamp", nowMs);
                current.put("languages", languages);
                if (filename != null) {
                    current.put("filename", filename);
                }
                current.put("tracks", tracks);
                updated.put(current);
            } catch (JSONException ignored) {
                // Only primitive values and a locally constructed array are written.
            }
        }

        for (int index = 0;
             index < previous.length() && updated.length() < MAX_ENTRIES;
             index++) {
            JSONObject item = previous.optJSONObject(index);
            if (!isUsableEntry(item, nowMs)) {
                continue;
            }
            if (sameKey(item, request.type, request.videoId, filename, languages)) {
                continue;
            }
            updated.put(item);
        }
        return updated.toString();
    }

    @Nullable
    static Lookup find(
            @Nullable String encoded,
            StremioConnectorStore.Content content,
            String[] preferredLanguages,
            long nowMs) {
        JSONArray entries = parseArray(encoded);
        String requestedFilename = StremioConnectorStore.normalizeFilename(
                content.mediaFilename);
        String languages = languageKey(preferredLanguages);
        if (languages.isEmpty()) {
            return null;
        }

        Lookup contentFallback = null;
        for (int index = 0; index < entries.length() && index < MAX_ENTRIES; index++) {
            JSONObject item = entries.optJSONObject(index);
            if (!isUsableEntry(item, nowMs)
                    || !content.type.equals(item.optString("type", ""))
                    || !content.id.equals(item.optString("id", ""))
                    || !languages.equals(item.optString("languages", ""))) {
                continue;
            }
            List<StremioPreloadedSubtitle.Parsed> tracks = parseTracks(
                    item.optJSONArray("tracks"));
            if (tracks.isEmpty()) {
                continue;
            }
            long timestampMs = item.optLong("timestamp", 0L);
            String cachedFilename = StremioConnectorStore.normalizeFilename(
                    item.optString("filename", null));
            boolean exactFilename = requestedFilename != null
                    && requestedFilename.equalsIgnoreCase(cachedFilename);
            Lookup lookup = new Lookup(
                    tracks,
                    exactFilename ? "exact_filename" : "content_id",
                    Math.max(0L, nowMs - timestampMs));
            if (exactFilename || requestedFilename == null) {
                return lookup;
            }
            if (contentFallback == null) {
                contentFallback = lookup;
            }
        }
        return contentFallback;
    }

    private static List<StremioPreloadedSubtitle.Parsed> parseTracks(
            @Nullable JSONArray values) {
        List<StremioPreloadedSubtitle.Parsed> tracks = new ArrayList<>();
        if (values == null) {
            return tracks;
        }
        for (int index = 0; index < values.length() && tracks.size() < MAX_TRACKS; index++) {
            StremioPreloadedSubtitle.Parsed parsed = StremioPreloadedSubtitle.parse(
                    values.optString(index, null));
            if (parsed != null) {
                tracks.add(parsed);
            }
        }
        return tracks;
    }

    private static boolean sameKey(
            JSONObject item,
            String type,
            String id,
            @Nullable String filename,
            String languages) {
        String cachedFilename = StremioConnectorStore.normalizeFilename(
                item.optString("filename", null));
        return type.equals(item.optString("type", ""))
                && id.equals(item.optString("id", ""))
                && languages.equals(item.optString("languages", ""))
                && (filename == null
                ? cachedFilename == null
                : filename.equalsIgnoreCase(cachedFilename));
    }

    private static boolean isUsableEntry(@Nullable JSONObject item, long nowMs) {
        if (item == null) {
            return false;
        }
        long timestampMs = item.optLong("timestamp", 0L);
        return timestampMs > 0L
                && timestampMs <= nowMs + 10_000L
                && nowMs - timestampMs <= MAX_AGE_MS
                && OpenSubtitlesV3Client.isSupportedContent(
                item.optString("type", ""), item.optString("id", ""));
    }

    private static String languageKey(@Nullable String[] preferredLanguages) {
        if (preferredLanguages == null || preferredLanguages.length == 0) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        for (String language : preferredLanguages) {
            String normalized = OpenSubtitlesV3Client.normalizeLanguage(language);
            if (normalized.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (String existing : key.toString().split(",")) {
                if (normalized.equals(existing)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                if (key.length() > 0) {
                    key.append(',');
                }
                key.append(normalized);
            }
        }
        return key.toString();
    }

    private static JSONArray parseArray(@Nullable String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH) {
            return new JSONArray();
        }
        try {
            return new JSONArray(encoded);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    static final class Lookup {
        final List<StremioPreloadedSubtitle.Parsed> tracks;
        final String match;
        final long ageMs;

        Lookup(List<StremioPreloadedSubtitle.Parsed> tracks, String match, long ageMs) {
            this.tracks = tracks;
            this.match = match;
            this.ageMs = ageMs;
        }
    }
}
