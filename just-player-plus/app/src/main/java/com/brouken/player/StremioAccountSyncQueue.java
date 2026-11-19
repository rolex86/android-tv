package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable, bounded queue of absolute progress checkpoints; contains no account credential. */
final class StremioAccountSyncQueue {
    private static final String PREFS_NAME = "justplayer_plus_stremio_account_sync";
    private static final String KEY_PENDING = "pending_checkpoints_v1";
    private static final int MAX_PENDING = 24;
    private static final int MAX_QUEUE_BYTES = 64 * 1024;
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    StremioAccountSyncQueue(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    boolean upsert(StremioLibraryItemPatch.Checkpoint checkpoint) {
        if (checkpoint == null || !checkpoint.isValid()) return false;
        synchronized (LOCK) {
            List<StremioLibraryItemPatch.Checkpoint> entries = load();
            for (int index = entries.size() - 1; index >= 0; index--) {
                if (entries.get(index).episode.equals(checkpoint.episode)) {
                    if (entries.get(index).completed && !checkpoint.completed) {
                        return true;
                    }
                    entries.remove(index);
                }
            }
            entries.add(checkpoint);
            while (entries.size() > MAX_PENDING) entries.remove(0);
            return save(entries);
        }
    }

    @Nullable
    StremioLibraryItemPatch.Checkpoint peek() {
        synchronized (LOCK) {
            List<StremioLibraryItemPatch.Checkpoint> entries = load();
            return entries.isEmpty() ? null : entries.get(0);
        }
    }

    void removeIfCurrent(StremioLibraryItemPatch.Checkpoint checkpoint) {
        if (checkpoint == null) return;
        synchronized (LOCK) {
            List<StremioLibraryItemPatch.Checkpoint> entries = load();
            for (int index = 0; index < entries.size(); index++) {
                StremioLibraryItemPatch.Checkpoint entry = entries.get(index);
                if (entry.episode.equals(checkpoint.episode)
                        && entry.capturedAtMs == checkpoint.capturedAtMs
                        && entry.completed == checkpoint.completed
                        && entry.positionMs == checkpoint.positionMs
                        && entry.durationMs == checkpoint.durationMs) {
                    entries.remove(index);
                    save(entries);
                    return;
                }
            }
        }
    }

    void clear() {
        synchronized (LOCK) {
            preferences.edit().remove(KEY_PENDING).commit();
        }
    }

    int size() {
        synchronized (LOCK) {
            return load().size();
        }
    }

    private List<StremioLibraryItemPatch.Checkpoint> load() {
        List<StremioLibraryItemPatch.Checkpoint> result = new ArrayList<>();
        String encoded = preferences.getString(KEY_PENDING, null);
        if (encoded == null || encoded.length() > MAX_QUEUE_BYTES) return result;
        try {
            JSONArray array = new JSONArray(encoded);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                StremioEpisodeId episode = StremioEpisodeId.parse(
                        item.optString("video_id", ""));
                StremioLibraryItemPatch.Checkpoint checkpoint =
                        new StremioLibraryItemPatch.Checkpoint(
                                episode,
                                item.optLong("position_ms", -1L),
                                item.optLong("duration_ms", -1L),
                                item.optBoolean("completed", false),
                                item.optLong("captured_at_ms", -1L));
                if (checkpoint.isValid()) result.add(checkpoint);
            }
        } catch (JSONException ignored) {
            // A corrupt optional queue is discarded instead of affecting playback.
        }
        return result;
    }

    private boolean save(List<StremioLibraryItemPatch.Checkpoint> entries) {
        JSONArray array = new JSONArray();
        for (StremioLibraryItemPatch.Checkpoint checkpoint : entries) {
            try {
                array.put(new JSONObject()
                        .put("video_id", checkpoint.episode.raw)
                        .put("position_ms", checkpoint.positionMs)
                        .put("duration_ms", checkpoint.durationMs)
                        .put("completed", checkpoint.completed)
                        .put("captured_at_ms", checkpoint.capturedAtMs));
            } catch (JSONException ignored) {
                return false;
            }
        }
        return preferences.edit().putString(KEY_PENDING, array.toString()).commit();
    }
}
