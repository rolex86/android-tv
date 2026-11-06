package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded local watched journal that can later feed an optional Trakt sync module. */
final class StremioWatchJournal {
    private static final String PREFS_NAME = "justplayer_plus_watch_journal";
    private static final String KEY_ENTRIES = "watched_episodes_v1";
    private static final int MAX_ENTRIES = 500;

    private final SharedPreferences preferences;

    StremioWatchJournal(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    synchronized void record(
            StremioEpisodeId episode,
            long watchedAtMs,
            @Nullable String seriesTitle,
            @Nullable String episodeTitle) {
        if (episode == null || watchedAtMs <= 0L) {
            return;
        }
        JSONArray updated = update(
                preferences.getString(KEY_ENTRIES, null),
                episode,
                watchedAtMs,
                seriesTitle,
                episodeTitle);
        preferences.edit().putString(KEY_ENTRIES, updated.toString()).apply();
    }

    static JSONArray update(
            @Nullable String encoded,
            StremioEpisodeId episode,
            long watchedAtMs,
            @Nullable String seriesTitle,
            @Nullable String episodeTitle) {
        JSONArray previous;
        try {
            previous = encoded == null ? new JSONArray() : new JSONArray(encoded);
        } catch (JSONException ignored) {
            previous = new JSONArray();
        }
        JSONArray result = new JSONArray();
        JSONObject current = new JSONObject();
        try {
            current.put("video_id", episode.raw);
            current.put("meta_id", episode.metaId);
            current.put("season", episode.season);
            current.put("episode", episode.episode);
            current.put("watched_at_ms", watchedAtMs);
            if (seriesTitle != null && !seriesTitle.trim().isEmpty()) {
                current.put("series_title", seriesTitle.trim());
            }
            if (episodeTitle != null && !episodeTitle.trim().isEmpty()) {
                current.put("episode_title", episodeTitle.trim());
            }
            result.put(current);
            for (int index = 0;
                 index < previous.length() && result.length() < MAX_ENTRIES;
                 index++) {
                JSONObject item = previous.optJSONObject(index);
                if (item != null
                        && !episode.raw.equals(item.optString("video_id", ""))) {
                    result.put(item);
                }
            }
        } catch (JSONException ignored) {
            return new JSONArray();
        }
        return result;
    }
}
