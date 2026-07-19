package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small bounded journal used to correlate Stremio's latest series request with player launch. */
final class StremioConnectorStore {
    private static final Object LOCK = new Object();
    private static final String PREFS_NAME = "justplayer_plus_stremio_connector";
    private static final String KEY_EVENTS = "stream_requests";
    private static final String LEGACY_KEY_CLAIMED_PAIR = "claimed_pair";
    private static final String KEY_CLAIMED_EPISODE = "claimed_episode";
    private static final int MAX_EVENTS = 24;
    private static final long MAX_EVENT_AGE_MS = 15 * 60_000L;
    private static final long DEDUPLICATE_WINDOW_MS = 2_000L;

    private final SharedPreferences preferences;

    StremioConnectorStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void record(String type, String id, long timestampMs) {
        if (!"series".equals(type) || StremioEpisodeId.parse(id) == null) {
            return;
        }
        synchronized (LOCK) {
            List<Event> events = readEvents();
            if (!events.isEmpty()) {
                Event last = events.get(events.size() - 1);
                long sinceLast = timestampMs - last.timestampMs;
                if (last.id.equals(id)
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
     * Claims Stremio's latest current-episode request. Android TV normally asks stream addons
     * only for the episode being launched, so Cinemeta derives the following episode from it.
     */
    @Nullable
    StremioEpisodeId claimRecentEpisode(long nowMs) {
        synchronized (LOCK) {
            List<Event> events = readEvents();
            Event event = findRecentEvent(events, nowMs);
            if (event == null) {
                return null;
            }
            String token = event.id + "\n" + event.timestampMs;
            if (token.equals(preferences.getString(KEY_CLAIMED_EPISODE, null))) {
                return null;
            }
            StremioEpisodeId episode = StremioEpisodeId.parse(event.id);
            if (episode == null) {
                return null;
            }

            // A single current request supersedes all older observations. A later Stremio
            // launch will record a fresh event (including when it automatically continues).
            writeEvents(new ArrayList<>());
            preferences.edit().putString(KEY_CLAIMED_EPISODE, token).apply();
            return episode;
        }
    }

    @Nullable
    static StremioEpisodeId findRecentEpisode(List<Event> events, long nowMs) {
        Event event = findRecentEvent(events, nowMs);
        return event == null ? null : StremioEpisodeId.parse(event.id);
    }

    @Nullable
    private static Event findRecentEvent(List<Event> events, long nowMs) {
        for (int index = events.size() - 1; index >= 0; index--) {
            Event event = events.get(index);
            if (event.timestampMs > nowMs + 10_000L
                    || nowMs - event.timestampMs > MAX_EVENT_AGE_MS) {
                continue;
            }
            if ("series".equals(event.type) && StremioEpisodeId.parse(event.id) != null) {
                return event;
            }
        }
        return null;
    }

    void clear() {
        synchronized (LOCK) {
            preferences.edit()
                    .remove(KEY_EVENTS)
                    .remove(LEGACY_KEY_CLAIMED_PAIR)
                    .remove(KEY_CLAIMED_EPISODE)
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
}
