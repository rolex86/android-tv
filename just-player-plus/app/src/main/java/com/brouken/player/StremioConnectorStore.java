package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small bounded journal used to correlate Stremio's current and prefetched-next requests. */
final class StremioConnectorStore {
    private static final Object LOCK = new Object();
    private static final String PREFS_NAME = "justplayer_plus_stremio_connector";
    private static final String KEY_EVENTS = "stream_requests";
    private static final String KEY_CLAIMED_PAIR = "claimed_pair";
    private static final int MAX_EVENTS = 24;
    private static final long MAX_PAIR_AGE_MS = 15 * 60_000L;
    private static final long MAX_EVENT_GAP_MS = 5 * 60_000L;
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
     * Returns only a pair that Stremio requested in order: current, then prefetched next.
     * A lone request is deliberately not treated as evidence that another episode exists.
     */
    @Nullable
    Pair claimRecentPair(long nowMs) {
        synchronized (LOCK) {
            List<Event> events = readEvents();
            Pair pair = findRecentPair(events, nowMs);
            if (pair == null) {
                return null;
            }
            String token = pair.current.raw + "\n" + pair.next.raw + "\n" + pair.observedAtMs;
            if (token.equals(preferences.getString(KEY_CLAIMED_PAIR, null))) {
                return null;
            }

            // Keep the observed next episode as the current seed for the following playback,
            // but discard older requests so a stale pair cannot be reused on another launch.
            List<Event> remaining = new ArrayList<>();
            remaining.add(new Event("series", pair.next.raw, pair.observedAtMs));
            for (Event event : events) {
                if (event.timestampMs > pair.observedAtMs) {
                    remaining.add(event);
                }
            }
            writeEvents(remaining);
            preferences.edit().putString(KEY_CLAIMED_PAIR, token).apply();
            return pair;
        }
    }

    @Nullable
    static Pair findRecentPair(List<Event> events, long nowMs) {
        for (int nextIndex = events.size() - 1; nextIndex > 0; nextIndex--) {
            Event nextEvent = events.get(nextIndex);
            if (nextEvent.timestampMs > nowMs + 10_000L
                    || nowMs - nextEvent.timestampMs > MAX_PAIR_AGE_MS) {
                continue;
            }
            StremioEpisodeId next = StremioEpisodeId.parse(nextEvent.id);
            if (next == null) {
                continue;
            }
            for (int currentIndex = nextIndex - 1; currentIndex >= 0; currentIndex--) {
                Event currentEvent = events.get(currentIndex);
                long gap = nextEvent.timestampMs - currentEvent.timestampMs;
                if (gap < 0L) {
                    continue;
                }
                if (gap > MAX_EVENT_GAP_MS) {
                    break;
                }
                StremioEpisodeId current = StremioEpisodeId.parse(currentEvent.id);
                if (current == null || current.equals(next)) {
                    continue;
                }
                if (current.belongsToSameSeries(next) && isAfter(current, next)) {
                    return new Pair(current, next, nextEvent.timestampMs);
                }
            }
        }
        return null;
    }

    void clear() {
        synchronized (LOCK) {
            preferences.edit().remove(KEY_EVENTS).remove(KEY_CLAIMED_PAIR).apply();
        }
    }

    private static boolean isAfter(StremioEpisodeId current, StremioEpisodeId next) {
        return next.season > current.season
                || next.season == current.season && next.episode > current.episode;
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

    static final class Pair {
        final StremioEpisodeId current;
        final StremioEpisodeId next;
        final long observedAtMs;

        Pair(StremioEpisodeId current, StremioEpisodeId next, long observedAtMs) {
            this.current = current;
            this.next = next;
            this.observedAtMs = observedAtMs;
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
