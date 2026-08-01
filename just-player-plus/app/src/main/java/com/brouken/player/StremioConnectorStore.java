package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Small bounded journal used to correlate Stremio's latest content request with player launch. */
final class StremioConnectorStore {
    private static final Object LOCK = new Object();
    private static final String PREFS_NAME = "justplayer_plus_stremio_connector";
    private static final String KEY_EVENTS = "stream_requests";
    private static final String LEGACY_KEY_CLAIMED_PAIR = "claimed_pair";
    private static final String KEY_CLAIMED_EPISODE = "claimed_episode";
    private static final String KEY_EXPECTED_EPISODE = "expected_episode";
    private static final String KEY_CONTENT_ASSOCIATIONS = "content_associations";
    private static final String KEY_SUBTITLE_PRELOADS = "subtitle_preloads_v1";
    private static final int MAX_EVENTS = 24;
    private static final int MAX_ASSOCIATIONS = 48;
    private static final long MAX_EVENT_AGE_MS = 15 * 60_000L;
    private static final long MAX_FILENAME_REFRESH_AGE_MS = 5_000L;
    private static final long MAX_EXPECTED_AGE_MS = 30_000L;
    private static final long MAX_ASSOCIATION_AGE_MS = 24L * 60L * 60_000L;
    private static final long DEDUPLICATE_WINDOW_MS = 2_000L;

    private final SharedPreferences preferences;

    StremioConnectorStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void record(String type, String id, long timestampMs) {
        record(type, id, null, timestampMs);
    }

    private void record(String type,
                        String id,
                        @Nullable String mediaFilename,
                        long timestampMs) {
        if (!isSupportedEvent(type, id)) {
            return;
        }
        String normalizedFilename = normalizeFilename(mediaFilename);
        synchronized (LOCK) {
            List<Event> events = readEvents();
            if (!events.isEmpty()) {
                Event last = events.get(events.size() - 1);
                long sinceLast = timestampMs - last.timestampMs;
                if (last.type.equals(type)
                        && last.id.equals(id)
                        && sinceLast >= 0L
                        && sinceLast <= DEDUPLICATE_WINDOW_MS) {
                    if (normalizedFilename == null
                            || normalizedFilename.equals(last.mediaFilename)) {
                        return;
                    }
                    events.remove(events.size() - 1);
                }
            }
            events.add(new Event(type, id, normalizedFilename, timestampMs));
            while (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
            writeEvents(events);
        }
    }

    /**
     * Claims Stremio's latest content request. A fresh movie request deliberately supersedes
     * stale series state so a next-episode timer can never be armed for a movie.
     */
    @Nullable
    Content claimRecentContent(long nowMs, @Nullable String launchIdentity) {
        synchronized (LOCK) {
            List<Event> events = readEvents();
            Event event = findRecentEvent(events, nowMs);
            StremioEpisodeId expected = claimExpectedEpisode(nowMs);
            if (expected != null && (event == null || !expected.raw.equals(event.id))) {
                Content content = Content.series(expected)
                        .withCorrelation("expected_next", 0L);
                rememberAssociation(launchIdentity, content, nowMs);
                return content;
            }
            if (event == null) {
                return findAssociation(launchIdentity, nowMs);
            }
            String token = event.type + "\n" + event.id + "\n" + event.timestampMs;
            if (token.equals(preferences.getString(KEY_CLAIMED_EPISODE, null))) {
                Content repeated = Content.fromEvent(event);
                if (repeated == null) {
                    return findAssociation(launchIdentity, nowMs);
                }
                repeated = repeated.withCorrelation(
                        "reused_request", Math.max(0L, nowMs - event.timestampMs));
                rememberAssociation(launchIdentity, repeated, nowMs);
                return repeated;
            }
            Content content = Content.fromEvent(event);
            if (content == null) {
                return null;
            }
            content = content.withCorrelation(
                    "recent_request", Math.max(0L, nowMs - event.timestampMs));

            // Keep the latest observation available while the same Stremio detail page reuses
            // its cached stream list. A request for different content is appended later and wins
            // because findRecentEvent() always scans from newest to oldest.
            preferences.edit()
                    .putString(KEY_CLAIMED_EPISODE, token)
                    .remove(KEY_EXPECTED_EPISODE)
                    .apply();
            rememberAssociation(launchIdentity, content, nowMs);
            return content;
        }
    }

    void recordContentAssociation(String type,
                                  String id,
                                  @Nullable String mediaFilename,
                                  long timestampMs) {
        if (!isSupportedEvent(type, id)) {
            return;
        }
        synchronized (LOCK) {
            record(type, id, mediaFilename, timestampMs);
            Content content = Content.fromValues(type, id);
            if (content != null) {
                content = content.withMediaFilename(mediaFilename);
                rememberAssociation(mediaFilename, content, timestampMs);
            }
        }
    }

    Content refreshContent(Content content, long nowMs) {
        synchronized (LOCK) {
            return refreshContent(readEvents(), content, nowMs);
        }
    }

    /**
     * Finds advisory content identity without consuming next-episode state or claiming the event.
     * This is used only before the first media item is created.
     */
    @Nullable
    Content findLaunchContent(long nowMs, @Nullable String launchIdentity) {
        synchronized (LOCK) {
            Content associated = findAssociation(launchIdentity, nowMs);
            if (associated != null) {
                return associated.withCorrelation(
                        "startup_association", associated.correlationAgeMs);
            }
            Event event = findRecentEvent(readEvents(), nowMs);
            Content content = event == null ? null : Content.fromEvent(event);
            return content == null ? null : content.withCorrelation(
                    "startup_recent_request", Math.max(0L, nowMs - event.timestampMs));
        }
    }

    void recordPreloadedSubtitles(
            StremioSubtitleRequest request,
            String[] preferredLanguages,
            List<OpenSubtitlesV3Client.Candidate> candidates,
            long nowMs) {
        synchronized (LOCK) {
            String encoded = StremioSubtitlePreloadCache.update(
                    preferences.getString(KEY_SUBTITLE_PRELOADS, null),
                    request,
                    preferredLanguages,
                    candidates,
                    nowMs);
            if ("[]".equals(encoded)) {
                preferences.edit().remove(KEY_SUBTITLE_PRELOADS).apply();
            } else {
                preferences.edit().putString(KEY_SUBTITLE_PRELOADS, encoded).apply();
            }
        }
    }

    @Nullable
    StremioSubtitlePreloadCache.Lookup findPreloadedSubtitles(
            Content content,
            String[] preferredLanguages,
            long nowMs) {
        synchronized (LOCK) {
            return StremioSubtitlePreloadCache.find(
                    preferences.getString(KEY_SUBTITLE_PRELOADS, null),
                    content,
                    preferredLanguages,
                    nowMs);
        }
    }

    static Content refreshContent(List<Event> events, Content content, long nowMs) {
        if (content.mediaFilename != null) {
            return content;
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            Event event = events.get(index);
            if (event.timestampMs > nowMs + 10_000L
                    || nowMs - event.timestampMs > MAX_FILENAME_REFRESH_AGE_MS
                    || !content.type.equals(event.type)
                    || !content.id.equals(event.id)
                    || event.mediaFilename == null) {
                continue;
            }
            return content.withMediaFilename(event.mediaFilename);
        }
        return content;
    }

    private void rememberAssociation(@Nullable String launchIdentity,
                                     Content content,
                                     long nowMs) {
        String identity = hashIdentity(launchIdentity);
        if (identity == null) {
            return;
        }
        JSONArray previous = readAssociations();
        JSONArray updated = new JSONArray();
        try {
            JSONObject current = new JSONObject();
            current.put("identity", identity);
            current.put("type", content.type);
            current.put("id", content.id);
            current.put("timestamp", nowMs);
            if (content.mediaFilename != null) {
                current.put("filename", content.mediaFilename);
            }
            updated.put(current);

            for (int index = 0;
                 index < previous.length() && updated.length() < MAX_ASSOCIATIONS;
                 index++) {
                JSONObject item = previous.optJSONObject(index);
                if (item == null || identity.equals(item.optString("identity", ""))) {
                    continue;
                }
                long timestamp = item.optLong("timestamp", 0L);
                if (timestamp <= nowMs + 10_000L
                        && nowMs - timestamp <= MAX_ASSOCIATION_AGE_MS) {
                    updated.put(item);
                }
            }
            preferences.edit().putString(
                    KEY_CONTENT_ASSOCIATIONS, updated.toString()).apply();
        } catch (JSONException ignored) {
            // Only primitive values are written, so this is defensive.
        }
    }

    @Nullable
    private Content findAssociation(@Nullable String launchIdentity, long nowMs) {
        String identity = hashIdentity(launchIdentity);
        if (identity == null) {
            return null;
        }
        JSONArray associations = readAssociations();
        for (int index = 0; index < associations.length(); index++) {
            JSONObject item = associations.optJSONObject(index);
            if (item == null || !identity.equals(item.optString("identity", ""))) {
                continue;
            }
            long timestamp = item.optLong("timestamp", 0L);
            if (timestamp > nowMs + 10_000L
                    || nowMs - timestamp > MAX_ASSOCIATION_AGE_MS) {
                return null;
            }
            Content content = Content.fromValues(
                    item.optString("type", ""), item.optString("id", ""));
            if (content == null) {
                return null;
            }
            content = content.withMediaFilename(item.optString("filename", null));
            return content.withCorrelation(
                    "remembered_association", Math.max(0L, nowMs - timestamp));
        }
        return null;
    }

    private JSONArray readAssociations() {
        String encoded = preferences.getString(KEY_CONTENT_ASSOCIATIONS, null);
        if (encoded == null) {
            return new JSONArray();
        }
        try {
            return new JSONArray(encoded);
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_CONTENT_ASSOCIATIONS).apply();
            return new JSONArray();
        }
    }

    @Nullable
    static String hashIdentity(@Nullable String launchIdentity) {
        if (launchIdentity == null || launchIdentity.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    launchIdentity.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return null;
        }
    }

    void expectEpisode(StremioEpisodeId episode, long nowMs) {
        if (episode == null) {
            return;
        }
        synchronized (LOCK) {
            preferences.edit().putString(
                    KEY_EXPECTED_EPISODE, episode.raw + "\n" + nowMs).apply();
        }
    }

    /** Cancels only the automatic-continuation hint while preserving fresh helper metadata. */
    void clearExpectedEpisode() {
        synchronized (LOCK) {
            preferences.edit().remove(KEY_EXPECTED_EPISODE).apply();
        }
    }

    @Nullable
    private StremioEpisodeId claimExpectedEpisode(long nowMs) {
        String encoded = preferences.getString(KEY_EXPECTED_EPISODE, null);
        preferences.edit().remove(KEY_EXPECTED_EPISODE).apply();
        if (encoded == null) {
            return null;
        }
        int separator = encoded.lastIndexOf('\n');
        if (separator <= 0 || separator == encoded.length() - 1) {
            return null;
        }
        try {
            long timestamp = Long.parseLong(encoded.substring(separator + 1));
            if (timestamp > nowMs + 10_000L || nowMs - timestamp > MAX_EXPECTED_AGE_MS) {
                return null;
            }
            return StremioEpisodeId.parse(encoded.substring(0, separator));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    static Content findRecentContent(List<Event> events, long nowMs) {
        Event event = findRecentEvent(events, nowMs);
        return event == null ? null : Content.fromEvent(event);
    }

    @Nullable
    private static Event findRecentEvent(List<Event> events, long nowMs) {
        for (int index = events.size() - 1; index >= 0; index--) {
            Event event = events.get(index);
            if (event.timestampMs > nowMs + 10_000L
                    || nowMs - event.timestampMs > MAX_EVENT_AGE_MS) {
                continue;
            }
            if (isSupportedEvent(event.type, event.id)) {
                return event;
            }
        }
        return null;
    }

    private static boolean isSupportedEvent(String type, String id) {
        if ("series".equals(type)) {
            return StremioEpisodeId.parse(id) != null;
        }
        return "movie".equals(type) && id != null && !id.trim().isEmpty();
    }

    void clear() {
        synchronized (LOCK) {
            preferences.edit()
                    .remove(KEY_EVENTS)
                    .remove(LEGACY_KEY_CLAIMED_PAIR)
                    .remove(KEY_CLAIMED_EPISODE)
                    .remove(KEY_EXPECTED_EPISODE)
                    .remove(KEY_CONTENT_ASSOCIATIONS)
                    .remove(KEY_SUBTITLE_PRELOADS)
                    .apply();
        }
    }

    private List<Event> readEvents() {
        List<Event> events = new ArrayList<>();
        String encoded = preferences.getString(KEY_EVENTS, null);
        if (encoded == null) {
            return events;
        }
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String type = item.optString("type", "");
                String id = item.optString("id", "");
                String filename = item.optString("filename", null);
                long timestamp = item.optLong("timestamp", 0L);
                if (!type.isEmpty() && !id.isEmpty() && timestamp > 0L) {
                    events.add(new Event(type, id, filename, timestamp));
                }
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_EVENTS).apply();
        }
        return events;
    }

    private void writeEvents(List<Event> events) {
        JSONArray array = new JSONArray();
        try {
            for (Event event : events) {
                JSONObject item = new JSONObject();
                item.put("type", event.type);
                item.put("id", event.id);
                item.put("timestamp", event.timestampMs);
                if (event.mediaFilename != null) {
                    item.put("filename", event.mediaFilename);
                }
                array.put(item);
            }
            preferences.edit().putString(KEY_EVENTS, array.toString()).apply();
        } catch (JSONException ignored) {
            // Only primitive values are written, so this is defensive.
        }
    }

    static final class Event {
        final String type;
        final String id;
        @Nullable final String mediaFilename;
        final long timestampMs;

        Event(String type, String id, long timestampMs) {
            this(type, id, null, timestampMs);
        }

        Event(String type,
              String id,
              @Nullable String mediaFilename,
              long timestampMs) {
            this.type = type;
            this.id = id;
            this.mediaFilename = normalizeFilename(mediaFilename);
            this.timestampMs = timestampMs;
        }
    }

    static final class Content {
        final String type;
        final String id;
        @Nullable final StremioEpisodeId episode;
        @Nullable final String mediaFilename;
        final String correlationSource;
        final long correlationAgeMs;

        private Content(String type,
                        String id,
                        @Nullable StremioEpisodeId episode,
                        @Nullable String mediaFilename,
                        String correlationSource,
                        long correlationAgeMs) {
            this.type = type;
            this.id = id;
            this.episode = episode;
            this.mediaFilename = normalizeFilename(mediaFilename);
            this.correlationSource = correlationSource;
            this.correlationAgeMs = correlationAgeMs;
        }

        static Content series(StremioEpisodeId episode) {
            return new Content(
                    "series", episode.raw, episode, null, "unspecified", -1L);
        }

        static Content movie(String id) {
            return new Content("movie", id, null, null, "unspecified", -1L);
        }

        Content withCorrelation(String source, long ageMs) {
            return new Content(
                    type, id, episode, mediaFilename, source, ageMs);
        }

        Content withMediaFilename(@Nullable String filename) {
            return new Content(
                    type, id, episode, filename, correlationSource, correlationAgeMs);
        }

        @Nullable
        static Content fromValues(String type, String id) {
            if (!isSupportedEvent(type, id)) {
                return null;
            }
            if ("movie".equals(type)) {
                return movie(id);
            }
            StremioEpisodeId episode = StremioEpisodeId.parse(id);
            return episode == null ? null : series(episode);
        }

        @Nullable
        static Content fromEvent(Event event) {
            Content content = fromValues(event.type, event.id);
            return content == null
                    ? null : content.withMediaFilename(event.mediaFilename);
        }

        boolean isSeries() {
            return episode != null;
        }
    }

    @Nullable
    static String normalizeFilename(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
