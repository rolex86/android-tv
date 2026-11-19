package com.brouken.player;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Builds and verifies the smallest safe full-object datastorePut update. */
final class StremioLibraryItemPatch {
    private static final long MAX_U32 = 0xffff_ffffL;

    static final class Checkpoint {
        final StremioEpisodeId episode;
        final long positionMs;
        final long durationMs;
        final boolean completed;
        final long capturedAtMs;

        Checkpoint(
                StremioEpisodeId episode,
                long positionMs,
                long durationMs,
                boolean completed,
                long capturedAtMs) {
            this.episode = episode;
            this.positionMs = Math.max(0L, positionMs);
            this.durationMs = Math.max(0L, durationMs);
            this.completed = completed;
            this.capturedAtMs = capturedAtMs;
        }

        boolean isValid() {
            return episode != null && capturedAtMs > 0L
                    && durationMs > 0L && positionMs <= durationMs;
        }
    }

    private StremioLibraryItemPatch() {
    }

    static JSONObject build(
            JSONObject before,
            Checkpoint checkpoint,
            List<String> videoIds,
            String capturedTimestamp,
            String mutationTimestamp) throws IOException, JSONException {
        if (before == null || checkpoint == null || !checkpoint.isValid()
                || !checkpoint.episode.metaId.equals(before.optString("_id", ""))
                || !"series".equals(before.optString("type", ""))) {
            throw new IOException("Invalid series checkpoint target");
        }
        if (!videoIds.contains(checkpoint.episode.raw)) {
            throw new IOException("Checkpoint episode is absent from Cinemeta ordering");
        }
        JSONObject updated = new JSONObject(before.toString());
        JSONObject state = updated.optJSONObject("state");
        if (state == null) {
            throw new IOException("Stremio library item has no state");
        }

        String previousVideoId = nullableString(state, "video_id");
        if (!checkpoint.episode.raw.equals(previousVideoId)) {
            long previousTimeWatched = nonNegativeLong(state, "timeWatched");
            state.put("overallTimeWatched", saturatingAdd(
                    nonNegativeLong(state, "overallTimeWatched"),
                    previousTimeWatched));
            state.put("timeWatched", 0L);
            state.put("flaggedWatched", 0);
            state.put("video_id", checkpoint.episode.raw);
        }

        state.put("lastWatched", capturedTimestamp);
        state.put("duration", checkpoint.durationMs);
        state.put("timeOffset", checkpoint.completed ? 0L : checkpoint.positionMs);

        if (checkpoint.completed) {
            boolean alreadyWatched = StremioWatchedBitfield.isWatched(
                    nullableString(state, "watched"),
                    videoIds,
                    checkpoint.episode.raw);
            state.put("watched", StremioWatchedBitfield.setWatched(
                    nullableString(state, "watched"),
                    videoIds,
                    checkpoint.episode.raw,
                    true));
            if (!alreadyWatched && nonNegativeLong(state, "flaggedWatched") == 0L) {
                state.put("timesWatched", saturatingIncrement(
                        nonNegativeLong(state, "timesWatched")));
            }
            state.put("flaggedWatched", 1);
        }
        updated.put("_mtime", mutationTimestamp);
        return updated;
    }

    static boolean verify(
            JSONObject before,
            JSONObject intended,
            JSONObject after,
            Checkpoint checkpoint,
            List<String> videoIds) throws IOException {
        if (!jsonEquals(intended, after)) {
            return false;
        }
        JSONObject state = after.optJSONObject("state");
        if (state == null
                || !checkpoint.episode.raw.equals(nullableString(state, "video_id"))
                || state.optLong("duration", -1L) != checkpoint.durationMs
                || state.optLong("timeOffset", -1L)
                != (checkpoint.completed ? 0L : checkpoint.positionMs)) {
            return false;
        }
        if (checkpoint.completed && !StremioWatchedBitfield.isWatched(
                nullableString(state, "watched"), videoIds, checkpoint.episode.raw)) {
            return false;
        }
        Set<String> allowed = changedPaths(before, intended);
        return allowed.equals(changedPaths(before, after));
    }

    static Set<String> changedPaths(Object before, Object after) {
        Set<String> paths = new HashSet<>();
        collectChangedPaths(before, after, "", paths);
        return paths;
    }

    static boolean jsonEquals(Object first, Object second) {
        if (first == second) return true;
        if (first == null || first == JSONObject.NULL) {
            return second == null || second == JSONObject.NULL;
        }
        if (second == null || second == JSONObject.NULL) return false;
        if (first instanceof JSONObject && second instanceof JSONObject) {
            JSONObject a = (JSONObject) first;
            JSONObject b = (JSONObject) second;
            List<String> keys = keys(a);
            if (!keys.equals(keys(b))) return false;
            for (String key : keys) {
                if (!jsonEquals(a.opt(key), b.opt(key))) return false;
            }
            return true;
        }
        if (first instanceof JSONArray && second instanceof JSONArray) {
            JSONArray a = (JSONArray) first;
            JSONArray b = (JSONArray) second;
            if (a.length() != b.length()) return false;
            for (int index = 0; index < a.length(); index++) {
                if (!jsonEquals(a.opt(index), b.opt(index))) return false;
            }
            return true;
        }
        if (first instanceof Number && second instanceof Number) {
            return first.toString().equals(second.toString());
        }
        return first.equals(second);
    }

    private static void collectChangedPaths(
            Object before, Object after, String prefix, Set<String> paths) {
        if (before instanceof JSONObject && after instanceof JSONObject) {
            JSONObject a = (JSONObject) before;
            JSONObject b = (JSONObject) after;
            Set<String> allKeys = new HashSet<>(keys(a));
            allKeys.addAll(keys(b));
            for (String key : allKeys) {
                String child = prefix.isEmpty() ? key : prefix + "." + key;
                if (!a.has(key) || !b.has(key)) {
                    paths.add(child);
                } else {
                    collectChangedPaths(a.opt(key), b.opt(key), child, paths);
                }
            }
            return;
        }
        if (before instanceof JSONArray && after instanceof JSONArray) {
            JSONArray a = (JSONArray) before;
            JSONArray b = (JSONArray) after;
            int length = Math.max(a.length(), b.length());
            for (int index = 0; index < length; index++) {
                String child = prefix + "[" + index + "]";
                if (index >= a.length() || index >= b.length()) {
                    paths.add(child);
                } else {
                    collectChangedPaths(a.opt(index), b.opt(index), child, paths);
                }
            }
            return;
        }
        if (!jsonEquals(before, after)) {
            paths.add(prefix);
        }
    }

    private static List<String> keys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        return keys;
    }

    @Nullable
    private static String nullableString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        String value = object.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static long nonNegativeLong(JSONObject object, String key) {
        return Math.max(0L, object.optLong(key, 0L));
    }

    private static long saturatingIncrement(long value) {
        return value >= MAX_U32 ? MAX_U32 : value + 1L;
    }

    private static long saturatingAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }
}
