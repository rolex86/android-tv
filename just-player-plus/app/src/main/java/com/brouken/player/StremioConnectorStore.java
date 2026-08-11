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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final String KEY_STREAM_FALLBACKS = "stream_fallbacks_v1";
    private static final int MAX_EVENTS = 24;
    private static final int MAX_ASSOCIATIONS = 48;
    private static final int MAX_FALLBACK_QUEUES = 8;
    private static final int MAX_STREAM_FALLBACKS = 50;
    private static final long MAX_EVENT_AGE_MS = 15 * 60_000L;
    private static final long MAX_FILENAME_REFRESH_AGE_MS = 5_000L;
    private static final long MAX_EXPECTED_AGE_MS = 30_000L;
    private static final long MAX_ASSOCIATION_AGE_MS = 24L * 60L * 60_000L;
    private static final long MAX_STREAM_FALLBACK_AGE_MS = 2L * 60L * 60_000L;
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
        return claimRecentContent(nowMs, launchIdentity, null);
    }

    @Nullable
    Content claimRecentContent(
            long nowMs,
            @Nullable String launchIdentity,
            @Nullable String alternateLaunchIdentity) {
        synchronized (LOCK) {
            List<Event> events = readEvents();
            Event event = findRecentEvent(events, nowMs);
            ExpectedEpisode expected = claimExpectedEpisode(nowMs);
            Content associated = findAssociation(launchIdentity, nowMs);
            if (associated == null) {
                associated = findAssociation(alternateLaunchIdentity, nowMs);
            }
            if (associated != null) {
                associated = associated.withCorrelation(
                        "launch_association", associated.correlationAgeMs);
                rememberAssociation(launchIdentity, associated, nowMs);
                rememberAssociation(alternateLaunchIdentity, associated, nowMs);
                return associated;
            }
            if (shouldUseExpectedEpisode(event, expected)) {
                Content content = Content.series(expected.episode)
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
     * Remembers the final ordered direct-URL streams returned by the Connector for exact content.
     * Torrent and external-page entries remain Stremio-owned and are never treated as playable
     * JustPlayer fallbacks.
     */
    void recordStreamFallbacks(String type, String id, String response, long nowMs) {
        Content content = Content.fromValues(type, id);
        if (content == null) {
            return;
        }
        List<StreamFallback> fallbacks = parseDirectFallbacks(response);
        synchronized (LOCK) {
            JSONArray previous = readStreamFallbackQueues();
            JSONArray updated = new JSONArray();
            if (!fallbacks.isEmpty()) {
                JSONObject current = new JSONObject();
                JSONArray streams = new JSONArray();
                try {
                    current.put("type", type);
                    current.put("id", id);
                    current.put("timestamp", nowMs);
                    for (StreamFallback fallback : fallbacks) {
                        JSONObject stream = new JSONObject();
                        stream.put("url", fallback.url);
                        if (fallback.label != null) {
                            stream.put("label", fallback.label);
                        }
                        streams.put(stream);
                    }
                    current.put("streams", streams);
                    updated.put(current);
                } catch (JSONException ignored) {
                    return;
                }
            }

            for (int index = 0;
                 index < previous.length() && updated.length() < MAX_FALLBACK_QUEUES;
                 index++) {
                JSONObject item = previous.optJSONObject(index);
                if (item == null
                        || (type.equals(item.optString("type", ""))
                        && id.equals(item.optString("id", "")))) {
                    continue;
                }
                long timestamp = item.optLong("timestamp", 0L);
                if (isFreshStreamFallback(timestamp, nowMs)) {
                    updated.put(item);
                }
            }
            if (updated.length() == 0) {
                preferences.edit().remove(KEY_STREAM_FALLBACKS).apply();
            } else {
                preferences.edit().putString(
                        KEY_STREAM_FALLBACKS, updated.toString()).apply();
            }

            if (!fallbacks.isEmpty()) {
                List<String> identities = new ArrayList<>();
                for (StreamFallback fallback : fallbacks) {
                    identities.add(fallback.url);
                }
                rememberAssociations(identities, content, nowMs);
            }
        }
    }

    @Nullable
    StreamFallback findNextStreamFallback(
            Content content,
            @Nullable String currentUrl,
            Set<String> attemptedUrls,
            long nowMs) {
        if (content == null || !content.isSeries() || currentUrl == null) {
            return null;
        }
        synchronized (LOCK) {
            JSONArray queues = readStreamFallbackQueues();
            for (int index = 0; index < queues.length(); index++) {
                JSONObject item = queues.optJSONObject(index);
                if (item == null
                        || !content.type.equals(item.optString("type", ""))
                        || !content.id.equals(item.optString("id", ""))
                        || !isFreshStreamFallback(
                        item.optLong("timestamp", 0L), nowMs)) {
                    continue;
                }
                List<StreamFallback> candidates = parseStoredFallbacks(
                        item.optJSONArray("streams"));
                return nextFallback(candidates, currentUrl, attemptedUrls);
            }
            return null;
        }
    }

    static List<StreamFallback> parseDirectFallbacks(@Nullable String response) {
        if (response == null) {
            return Collections.emptyList();
        }
        try {
            JSONArray streams = new JSONObject(response).optJSONArray("streams");
            if (streams == null) {
                return Collections.emptyList();
            }
            List<StreamFallback> result = new ArrayList<>();
            Set<String> urls = new LinkedHashSet<>();
            for (int index = 0;
                 index < streams.length() && result.size() < MAX_STREAM_FALLBACKS;
                 index++) {
                JSONObject stream = streams.optJSONObject(index);
                if (stream == null) {
                    continue;
                }
                String url = stream.optString("url", "").trim();
                if (!isDirectPlaybackUrl(url) || !urls.add(url)) {
                    continue;
                }
                String name = normalizeFilename(stream.optString("name", null));
                String title = normalizeFilename(stream.optString("title", null));
                String label = name == null ? title
                        : title == null ? name : name + " | " + title;
                result.add(new StreamFallback(url, label));
            }
            return result;
        } catch (JSONException | RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    @Nullable
    static StreamFallback nextFallback(
            List<StreamFallback> candidates,
            String currentUrl,
            Set<String> attemptedUrls) {
        boolean currentBelongsToQueue = false;
        for (StreamFallback candidate : candidates) {
            if (candidate.url.equals(currentUrl)) {
                currentBelongsToQueue = true;
                break;
            }
        }
        if (!currentBelongsToQueue) {
            return null;
        }
        Set<String> attempted = attemptedUrls == null
                ? Collections.emptySet() : attemptedUrls;
        for (StreamFallback candidate : candidates) {
            if (!candidate.url.equals(currentUrl) && !attempted.contains(candidate.url)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<StreamFallback> parseStoredFallbacks(@Nullable JSONArray streams) {
        if (streams == null) {
            return Collections.emptyList();
        }
        List<StreamFallback> result = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (int index = 0;
             index < streams.length() && result.size() < MAX_STREAM_FALLBACKS;
             index++) {
            JSONObject stream = streams.optJSONObject(index);
            if (stream == null) {
                continue;
            }
            String url = stream.optString("url", "").trim();
            if (!isDirectPlaybackUrl(url) || !urls.add(url)) {
                continue;
            }
            result.add(new StreamFallback(
                    url, normalizeFilename(stream.optString("label", null))));
        }
        return result;
    }

    private JSONArray readStreamFallbackQueues() {
        String encoded = preferences.getString(KEY_STREAM_FALLBACKS, null);
        if (encoded == null) {
            return new JSONArray();
        }
        try {
            return new JSONArray(encoded);
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_STREAM_FALLBACKS).apply();
            return new JSONArray();
        }
    }

    private static boolean isFreshStreamFallback(long timestampMs, long nowMs) {
        return timestampMs > 0L
                && timestampMs <= nowMs + 10_000L
                && nowMs - timestampMs <= MAX_STREAM_FALLBACK_AGE_MS;
    }

    private static boolean isDirectPlaybackUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://")
                || normalized.startsWith("https://")
                || normalized.startsWith("rtsp://");
    }

    /**
     * Finds advisory content identity without consuming next-episode state or claiming the event.
     * This is used only before the first media item is created.
     */
    @Nullable
    Content findLaunchContent(long nowMs, @Nullable String launchIdentity) {
        return findLaunchContent(nowMs, launchIdentity, null);
    }

    @Nullable
    Content findLaunchContent(
            long nowMs,
            @Nullable String launchIdentity,
            @Nullable String alternateLaunchIdentity) {
        synchronized (LOCK) {
            Content associated = findAssociation(launchIdentity, nowMs);
            if (associated == null) {
                associated = findAssociation(alternateLaunchIdentity, nowMs);
            }
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
        rememberAssociations(
                Collections.singletonList(launchIdentity), content, nowMs);
    }

    private void rememberAssociations(
            List<String> launchIdentities,
            Content content,
            long nowMs) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (String launchIdentity : launchIdentities) {
            String identity = hashIdentity(launchIdentity);
            if (identity != null) {
                identities.add(identity);
            }
        }
        if (identities.isEmpty()) {
            return;
        }
        JSONArray previous = readAssociations();
        JSONArray updated = new JSONArray();
        try {
            for (String identity : identities) {
                if (updated.length() >= MAX_ASSOCIATIONS) {
                    break;
                }
                JSONObject current = new JSONObject();
                current.put("identity", identity);
                current.put("type", content.type);
                current.put("id", content.id);
                current.put("timestamp", nowMs);
                if (content.mediaFilename != null) {
                    current.put("filename", content.mediaFilename);
                }
                updated.put(current);
            }

            for (int index = 0;
                 index < previous.length() && updated.length() < MAX_ASSOCIATIONS;
                 index++) {
                JSONObject item = previous.optJSONObject(index);
                if (item == null
                        || identities.contains(item.optString("identity", ""))) {
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
    private ExpectedEpisode claimExpectedEpisode(long nowMs) {
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
            StremioEpisodeId episode = StremioEpisodeId.parse(
                    encoded.substring(0, separator));
            return episode == null ? null : new ExpectedEpisode(episode, timestamp);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean shouldUseExpectedEpisode(
            @Nullable Event event,
            @Nullable ExpectedEpisode expected) {
        return expected != null
                && (event == null || event.timestampMs < expected.timestampMs);
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
                    .remove(KEY_STREAM_FALLBACKS)
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

    static final class ExpectedEpisode {
        final StremioEpisodeId episode;
        final long timestampMs;

        ExpectedEpisode(StremioEpisodeId episode, long timestampMs) {
            this.episode = episode;
            this.timestampMs = timestampMs;
        }
    }

    static final class StreamFallback {
        final String url;
        @Nullable final String label;

        StreamFallback(String url, @Nullable String label) {
            this.url = url;
            this.label = label;
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
