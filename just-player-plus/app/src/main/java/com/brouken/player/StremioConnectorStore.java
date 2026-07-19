package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    private static final int MAX_EVENTS = 24;
    private static final long MAX_EVENT_AGE_MS = 15 * 60_000L;
    private static final long MAX_EXPECTED_AGE_MS = 30_000L;
    private static final long DEDUPLICATE_WINDOW_MS = 2_000L;

    private final SharedPreferences preferences;

    StremioConnectorStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void record(String type, String id, long timestampMs) {
        if (!isSupportedEvent(type, id)) {
            return;
        }
        synchronized (LOCK) {
            List<Event> events = readEvents();
            if (!events.isEmpty()) {
                Event last = events.get(events.size() - 1);
                long sinceLast = timestampMs - last.timestampMs;
                if (last.type.equals(type)
                        && last.id.equals(id)
                        && sinceLast >= 0L
                        && sinceLast <= DEDUPLICATE_WINDOW_MS) {
                    return;
                }
            }
            events.add(new Event(type, id, timestampMs));
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
    Content claimRecentContent(long nowMs) {
        synchronized (LOCK) {
            List<Event> events = readEvents();
            Event event = findRecentEvent(events, nowMs);
            if (event == null) {
                StremioEpisodeId expected = claimExpectedEpisode(nowMs);
                return expected == null ? null : Content.series(expected);
            }
            String token = event.type + "\n" + event.id + "\n" + event.timestampMs;
            if (token.equals(preferences.getString(KEY_CLAIMED_EPISODE, null))) {
                return null;
            }
            Content content = Content.fromEvent(event);
            if (content == null) {
                return null;
            }

            // A single current request supersedes all older observations. A later Stremio
            // launch will record a fresh event (including when it automatically continues).
            writeEvents(new ArrayList<>());
            preferences.edit()
                    .putString(KEY_CLAIMED_EPISODE, token)
                    .remove(KEY_EXPECTED_EPISODE)
                    .apply();
            return content;
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
                long timestamp = item.optLong("timestamp", 0L);
                if (!type.isEmpty() && !id.isEmpty() && timestamp > 0L) {
                    events.add(new Event(type, id, timestamp));
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
        final long timestampMs;

        Event(String type, String id, long timestampMs) {
            this.type = type;
            this.id = id;
            this.timestampMs = timestampMs;
        }
    }

    static final class Content {
        final String type;
        final String id;
        @Nullable final StremioEpisodeId episode;

        private Content(String type, String id, @Nullable StremioEpisodeId episode) {
            this.type = type;
            this.id = id;
            this.episode = episode;
        }

        static Content series(StremioEpisodeId episode) {
            return new Content("series", episode.raw, episode);
        }

        static Content movie(String id) {
            return new Content("movie", id, null);
        }

        @Nullable
        static Content fromEvent(Event event) {
            if ("movie".equals(event.type) && isSupportedEvent(event.type, event.id)) {
                return movie(event.id);
            }
            StremioEpisodeId episode = StremioEpisodeId.parse(event.id);
            return episode == null ? null : series(episode);
        }

        boolean isSeries() {
            return episode != null;
        }
    }
}
